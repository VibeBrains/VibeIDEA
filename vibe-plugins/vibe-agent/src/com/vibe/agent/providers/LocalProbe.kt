// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Finding a local model server that is ALREADY running on this machine.
 *
 * Ollama and LM Studio are installed by people who then have to describe them in `providers.json`
 * by hand — an endpoint everyone types identically, a protocol that is always the same, and a model
 * list the server will happily produce itself. The whole configuration is one HTTP request away,
 * and asking a person to write it down is asking them to do the computer's job.
 *
 * What this must NOT do is add anything by itself. A provider appearing in the config because
 * something answered on a port is indistinguishable from a misconfiguration, so the probe reports
 * and the person decides.
 */
object LocalProbe {
  /** A local server we know how to talk to, by the port it traditionally uses. */
  data class Candidate(
    val id: String,
    val name: String,
    val baseUrl: String,
    /** Path that lists models: Ollama has its own, everything else copies OpenAI. */
    val probePath: String,
    val protocol: String,
    val ollamaStyle: Boolean = false,
  )

  data class Found(val candidate: Candidate, val models: List<String>)

  val CANDIDATES: List<Candidate> = listOf(
    Candidate("ollama", "Ollama", "http://localhost:11434/v1", "http://localhost:11434/api/tags", "openai", ollamaStyle = true),
    Candidate("lmstudio", "LM Studio", "http://localhost:1234/v1", "http://localhost:1234/v1/models", "openai"),
    Candidate("llamacpp", "llama.cpp", "http://localhost:8080/v1", "http://localhost:8080/v1/models", "openai"),
    Candidate("jan", "Jan", "http://localhost:1337/v1", "http://localhost:1337/v1/models", "openai"),
  )

  /** Both shapes: Ollama answers `{"models":[{"name":…}]}`, the rest answer `{"data":[{"id":…}]}`. */
  fun parseModels(body: String): List<String> {
    val root = runCatching { Json { ignoreUnknownKeys = true }.parseToJsonElement(body).jsonObject }.getOrNull()
      ?: return emptyList()
    val array = (root["models"] as? JsonArray) ?: (root["data"] as? JsonArray) ?: return emptyList()
    return array.mapNotNull { element ->
      val entry = element as? JsonObject ?: return@mapNotNull null
      val id = entry["id"]?.jsonPrimitive?.contentOrNull ?: entry["name"]?.jsonPrimitive?.contentOrNull
      id?.trim()?.takeIf { it.isNotEmpty() }
    }.distinct()
  }

  /**
   * Is this candidate already described in the project's providers?
   *
   * Matched by HOST AND PORT rather than by id: people name their entries whatever they like, and
   * offering to add a second Ollama because the first one is called `local` would be the kind of
   * help that produces duplicates.
   */
  fun isConfigured(candidate: Candidate, providers: List<ProviderEntry>): Boolean =
    providers.any { entry -> authorityOf(entry.baseURL) != null && authorityOf(entry.baseURL) == authorityOf(candidate.baseUrl) }

  fun authorityOf(url: String?): String? {
    val text = url?.trim().orEmpty()
    if (text.isEmpty()) return null
    val afterScheme = text.substringAfter("://", text)
    val authority = afterScheme.substringBefore('/').substringAfterLast('@').lowercase()
    if (authority.isEmpty()) return null
    // localhost and 127.0.0.1 are the same machine; treating them as different endpoints would
    // offer to add a duplicate to anyone who typed the other spelling.
    val host = authority.substringBeforeLast(':', authority)
    val port = if (':' in authority) authority.substringAfterLast(':') else ""
    val canonicalHost = if (host == "127.0.0.1" || host == "[::1]") "localhost" else host
    return if (port.isEmpty()) canonicalHost else canonicalHost + ":" + port
  }

  /** The provider entry a person would have written by hand — offered, never applied silently. */
  fun suggestedJson(found: Found): String = buildString {
    appendLine("{")
        appendLine("  \"id\": \"" + found.candidate.id + "\",")
    appendLine("  \"name\": \"" + found.candidate.name + "\",")
    appendLine("  \"protocol\": \"" + found.candidate.protocol + "\",")
    appendLine("  \"baseURL\": \"" + found.candidate.baseUrl + "\",")
    appendLine("  \"auth\": { \"type\": \"none\" },")
    appendLine("  \"models\": [")
    appendLine(found.models.take(MAX_SUGGESTED_MODELS).joinToString(",\n") { "    { \"id\": \"" + it + "\" }" })
    appendLine("  ]")
    append("}")
  }

  const val MAX_SUGGESTED_MODELS = 10
}
