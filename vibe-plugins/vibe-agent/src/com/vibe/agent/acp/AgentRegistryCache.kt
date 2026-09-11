// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import com.intellij.openapi.application.PathManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * The last registry answer, kept with its ETag.
 *
 * A second look at the catalog then costs a 304 instead of the whole file, and without a network
 * the last known catalog is shown — with its date, because a list that looks current and is a week
 * old sends people after agents that have moved on. A snapshot seeded into the IDE was rejected in
 * research: versions in the registry are pinned several times a day.
 */
object AgentRegistryCache {
  data class Cached(val body: String, val etag: String?, val fetchedAtMs: Long)

  sealed interface Outcome {
    /** A new catalog; to be kept with its [etag]. */
    data class Fresh(val body: String, val etag: String?) : Outcome

    /** The server says the kept catalog is current. */
    data class NotModified(val cached: Cached) : Outcome

    /** No usable answer; the kept catalog is shown, and [reason] says why the fresh one is not. */
    data class Offline(val cached: Cached, val reason: String) : Outcome

    /** No answer and nothing kept. */
    data class Failed(val reason: String) : Outcome
  }

  private const val HTTP_NOT_MODIFIED = 304
  private val HTTP_OK = 200..299

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * What to show, from the answer (or its absence) and what is kept. Pure.
   *
   * @param status the HTTP status, or null when there was no answer at all ([failure] says why).
   */
  fun decide(status: Int?, body: String?, etag: String?, failure: String?, cached: Cached?): Outcome {
    val reason = failure ?: "HTTP $status"
    return when {
      status == HTTP_NOT_MODIFIED && cached != null -> Outcome.NotModified(cached)
      status != null && status in HTTP_OK && body != null -> Outcome.Fresh(body, etag)
      cached != null -> Outcome.Offline(cached, reason)
      else -> Outcome.Failed(reason)
    }
  }

  fun file(): Path = Path.of(PathManager.getSystemPath(), "vibe", "acpRegistry.json")

  fun load(file: Path = file()): Cached? = runCatching {
    val o = json.parseToJsonElement(Files.readString(file)).jsonObject
    Cached(
      body = o.getValue("body").jsonPrimitive.content,
      etag = o["etag"]?.jsonPrimitive?.contentOrNull,
      fetchedAtMs = o["fetchedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
    )
  }.getOrNull()

  /** Best effort: a cache that cannot be written costs a full download next time, nothing more. */
  fun save(cached: Cached, file: Path = file()) {
    runCatching {
      Files.createDirectories(file.parent)
      Files.writeString(file, buildJsonObject {
        put("body", cached.body)
        cached.etag?.let { put("etag", it) }
        put("fetchedAt", cached.fetchedAtMs)
      }.toString())
    }
  }
}
