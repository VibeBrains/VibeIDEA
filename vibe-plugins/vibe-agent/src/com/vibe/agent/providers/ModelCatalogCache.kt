// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.intellij.openapi.application.PathManager
import com.vibe.agent.i18n.VibeI18n.t
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * Last known model catalog per provider, so the picker is full on the FIRST frame
 * instead of after a network round trip to every provider (which costs tens of
 * seconds when one of them times out).
 *
 * Rules that make the cache safe rather than merely fast:
 * - it is served ALWAYS and refreshed in background ALWAYS — no TTL, because a stale
 *   catalog is never worse than an empty one, and an expiry would be one more knob;
 * - only a SUCCESSFUL fetch writes; a 401/timeout leaves yesterday's catalog alone;
 * - an entry carries the fingerprint of the endpoint it came from, so pointing a
 *   provider at another baseURL/fetch URL discards the catalog of the old one;
 * - it lives in the IDE system (cache) path, not in `~/.vibe`: this is regenerable
 *   data, and `.vibe` is for files the user edits.
 */
object ModelCatalogCache {
  /** One provider's catalog as it was last fetched. */
  data class Entry(val fingerprint: String, val modelIds: List<String>, val fetchedAtMs: Long)

  private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

  private const val VERSION = 1

  fun file(): Path = Path.of(PathManager.getSystemPath(), "vibe", "modelCatalogs.json")

  /** Identity of the endpoint an entry came from: a moved provider must not inherit the old catalog. */
  fun fingerprint(provider: ProviderEntry): String =
    (provider.baseURL.orEmpty()) + "|" + (provider.modelsFetch?.url.orEmpty())

  // --- pure core (no I/O — this is what the tests drive) ---

  /**
   * Adds cached model ids as [ModelEntry] to providers that do not declare them, leaving
   * hand-declared models untouched. An entry whose fingerprint no longer matches the
   * provider is ignored — the catalog belonged to a different endpoint.
   */
  fun merge(providers: List<ProviderEntry>, cache: Map<String, Entry>): List<ProviderEntry> =
    providers.map { p ->
      val entry = cache[p.id] ?: return@map p
      if (entry.fingerprint != fingerprint(p)) return@map p
      val known = p.models.map { it.id }.toSet()
      val extra = entry.modelIds.filter { it !in known }.map { ModelEntry(id = it) }
      if (extra.isEmpty()) p else p.copy(models = p.models + extra)
    }

  /** Human-readable age of a cached catalog, for the log line that explains where models came from. */
  fun ageText(fetchedAtMs: Long, nowMs: Long): String {
    val minutes = (nowMs - fetchedAtMs).coerceAtLeast(0L) / 60_000L
    return when {
      minutes < 1 -> t("cache.age.justNow")
      minutes < 60 -> age(minutes, "minute")
      minutes < 24 * 60 -> age(minutes / 60, "hour")
      else -> age(minutes / (24 * 60), "day")
    }
  }

  /** Plural form comes from the catalogue: other languages count differently than Russian does. */
  private fun age(count: Long, unit: String): String {
    val form = when {
      count % 10 == 1L && count % 100 != 11L -> "one"
      count % 10 in 2..4 && count % 100 !in 12..14 -> "few"
      else -> "many"
    }
    return t("cache.age.minutes", "count" to count, "word" to t("cache.word.$unit.$form"))
  }

  fun decode(text: String): Map<String, Entry> {
    val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyMap()
    val providers = root["providers"]?.jsonObject ?: return emptyMap()
    return providers.mapNotNull { (id, el) ->
      val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
      val ids = runCatching { o["models"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } }
        .getOrNull() ?: return@mapNotNull null
      val fingerprint = o["fingerprint"]?.jsonPrimitive?.contentOrNull ?: ""
      val at = o["fetchedAt"]?.jsonPrimitive?.longOrNull ?: 0L
      id to Entry(fingerprint, ids, at)
    }.toMap()
  }

  fun encode(cache: Map<String, Entry>): String = json.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
      put("version", VERSION)
      put("providers", JsonObject(cache.mapValues { (_, e) ->
        buildJsonObject {
          put("fingerprint", e.fingerprint)
          put("fetchedAt", e.fetchedAtMs)
          put("models", JsonArray(e.modelIds.map { JsonPrimitive(it) }))
        }
      }))
    },
  )

  // --- I/O (best effort: a cache that fails to load must never break the IDE) ---

  @Synchronized
  fun load(): Map<String, Entry> {
    val path = file()
    if (!Files.exists(path)) return emptyMap()
    return runCatching { decode(Files.readString(path)) }.getOrDefault(emptyMap())
  }

  /** Replaces the entries of the given providers, keeping every other provider's catalog. */
  @Synchronized
  fun put(updates: Map<String, Entry>) {
    if (updates.isEmpty()) return
    runCatching {
      val path = file()
      Files.createDirectories(path.parent)
      Files.writeString(path, encode(load() + updates))
    }
  }
}
