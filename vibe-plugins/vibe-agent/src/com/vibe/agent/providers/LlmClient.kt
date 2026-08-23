// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

data class ChatMessage(val role: String, val text: String)

/**
 * Direct streaming chat against a provider endpoint.
 * Wire protocols mirror VibeIDE: "openai" (chat/completions SSE) and
 * "anthropic" (messages SSE; the base URL must already include the versioned
 * root — the client appends only the method name, per the VibeIDE spec).
 * Model-level extraBody is merged into the request verbatim (vendor quirks).
 * Pure transport: no IDE types in here.
 */
class LlmClient(private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()) {
  private val json = Json { ignoreUnknownKeys = true }
  @Volatile private var cancelled: () -> Boolean = { false }

  /** Blocking call; invoke from a pooled thread. onDelta receives text chunks as they stream. */
  fun chat(
    provider: ResolvedProvider,
    model: ModelEntry,
    messages: List<ChatMessage>,
    isCancelled: () -> Boolean = { false },
    onDelta: (String) -> Unit,
  ) {
    this.cancelled = isCancelled
    when (provider.protocol) {
      "anthropic" -> anthropicChat(provider, model, messages, onDelta)
      "gemini" -> geminiChat(provider, model, messages, onDelta)
      else -> openAiChat(provider, model, messages, onDelta)
    }
  }

  /** GET model catalog; openai-style {data:[{id}]} and gemini-style {models:[{name}]} are both understood. */
  fun listModels(provider: ResolvedProvider, fetchUrl: String?): List<String> {
    val url = if (!fetchUrl.isNullOrBlank()) fetchUrl else provider.baseUrl.trimEnd('/') + "/models"
    val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET()
    provider.apiKey?.let { key ->
      when (provider.entry.auth.type) {
        "header" -> builder.header(provider.entry.auth.name ?: "x-api-key", key)
        "query" -> {} // ключ уже должен быть в fetchUrl; для дефолта добавим ниже
        else -> builder.header("Authorization", "Bearer " + key)
      }
    }
    val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) throw RuntimeException("HTTP " + response.statusCode())
    val root = json.parseToJsonElement(response.body()).jsonObject
    val arr = root["data"]?.jsonArray ?: root["models"]?.jsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
      val o = el.jsonObject
      o["id"]?.jsonPrimitive?.contentOrNull ?: o["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/")
    }
  }

  /**
   * FIM completion over the legacy `/completions` endpoint (openai protocol only) —
   * prefix/suffix as API fields, special tokens are the server's business (VibeIDE approach).
   * Non-streaming; max_tokens mirrors VibeIDE: 300 cloud / 96 local.
   */
  fun fimComplete(provider: ResolvedProvider, model: ModelEntry, prefix: String, suffix: String, stop: List<String>): String {
    val body = withExtras(buildJsonObject {
      put("model", model.id)
      put("prompt", prefix)
      put("suffix", suffix)
      put("stream", false)
      put("max_tokens", if (provider.isLocal) 96 else 300)
      if (stop.isNotEmpty()) put("stop", JsonArray(stop.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }, model.extraBody)
    val request = requestBuilder(provider, "completions")
      .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
      .build()
    val response = http.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) throw RuntimeException("HTTP " + response.statusCode() + ": " + response.body().take(300))
    return json.parseToJsonElement(response.body()).jsonObject["choices"]?.jsonArray?.firstOrNull()
      ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
  }

  private fun geminiChat(provider: ResolvedProvider, model: ModelEntry, messages: List<ChatMessage>, onDelta: (String) -> Unit) {
    val system = messages.filter { it.role == "system" }.joinToString("\n") { it.text }
    val body = withExtras(buildJsonObject {
      if (system.isNotBlank()) put("systemInstruction", buildJsonObject {
        put("parts", JsonArray(listOf(buildJsonObject { put("text", system) })))
      })
      put("contents", JsonArray(messages.filter { it.role != "system" }.map { m ->
        buildJsonObject {
          put("role", if (m.role == "assistant") "model" else "user")
          put("parts", JsonArray(listOf(buildJsonObject { put("text", m.text) })))
        }
      }))
      put("generationConfig", buildJsonObject {
        model.temperature?.let { put("temperature", it) }
        model.topP?.let { put("topP", it) }
        model.topK?.let { put("topK", it) }
        model.maxOutputTokens?.let { put("maxOutputTokens", it) }
      })
    }, model.extraBody)
    var url = provider.baseUrl.trimEnd('/') + "/models/" + model.id + ":streamGenerateContent?alt=sse"
    val key = provider.apiKey
    if (key != null && provider.entry.auth.type == "query") {
      url += "&" + (provider.entry.auth.name ?: "key") + "=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
    }
    val builder = HttpRequest.newBuilder(URI.create(url))
      .timeout(Duration.ofMillis(provider.entry.timeoutMs ?: 600_000L))
      .header("Content-Type", "application/json")
    provider.entry.headers.forEach { (k, v) -> builder.header(k, v) }
    if (key != null && provider.entry.auth.type != "query") builder.header("x-goog-api-key", key)
    val request = builder.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
    streamSse(request) { data ->
      val text = json.parseToJsonElement(data).jsonObject["candidates"]?.jsonArray?.firstOrNull()
        ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray?.firstOrNull()
        ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
      if (text != null) onDelta(text)
    }
  }

  private fun openAiChat(provider: ResolvedProvider, model: ModelEntry, messages: List<ChatMessage>, onDelta: (String) -> Unit) {
    val body = withExtras(buildJsonObject {
      put("model", model.id)
      put("stream", true)
      model.temperature?.let { put("temperature", it) }
      model.topP?.let { put("top_p", it) }
      model.maxOutputTokens?.let { put("max_tokens", it) }
      put("messages", JsonArray(messages.map { m ->
        buildJsonObject {
          put("role", m.role)
          put("content", m.text)
        }
      }))
    }, model.extraBody)
    val request = requestBuilder(provider, "chat/completions")
      .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
      .build()
    streamSse(request) { data ->
      if (data == "[DONE]") return@streamSse
      val delta = json.parseToJsonElement(data).jsonObject["choices"]?.jsonArray?.firstOrNull()
        ?.jsonObject?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
      if (delta != null) onDelta(delta)
    }
  }

  private fun anthropicChat(provider: ResolvedProvider, model: ModelEntry, messages: List<ChatMessage>, onDelta: (String) -> Unit) {
    val system = messages.filter { it.role == "system" }.joinToString("\n") { it.text }
    val body = withExtras(buildJsonObject {
      put("model", model.id)
      put("stream", true)
      put("max_tokens", model.maxOutputTokens ?: 8192)
      model.temperature?.let { put("temperature", it) }
      model.topP?.let { put("top_p", it) }
      model.topK?.let { put("top_k", it) }
      if (system.isNotBlank()) put("system", system)
      put("messages", JsonArray(messages.filter { it.role != "system" }.map { m ->
        buildJsonObject {
          put("role", m.role)
          put("content", m.text)
        }
      }))
    }, model.extraBody)
    val request = requestBuilder(provider, "messages")
      .header("anthropic-version", "2023-06-01")
      .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
      .build()
    streamSse(request) { data ->
      val obj = json.parseToJsonElement(data).jsonObject
      if (obj["type"]?.jsonPrimitive?.contentOrNull == "content_block_delta") {
        val text = obj["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
        if (text != null) onDelta(text)
      }
    }
  }

  private fun requestBuilder(provider: ResolvedProvider, method: String): HttpRequest.Builder {
    val entry = provider.entry
    var url = provider.baseUrl.trimEnd('/') + "/" + method
    val queryParams = LinkedHashMap(entry.query)
    if (entry.auth.type == "query" && entry.auth.name != null && provider.apiKey != null) {
      queryParams[entry.auth.name] = provider.apiKey
    }
    if (queryParams.isNotEmpty()) {
      url += "?" + queryParams.entries.joinToString("&") {
        URLEncoder.encode(it.key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(it.value, StandardCharsets.UTF_8)
      }
    }
    val builder = HttpRequest.newBuilder(URI.create(url))
      .timeout(Duration.ofMillis(entry.timeoutMs ?: 600_000L))
      .header("Content-Type", "application/json")
    entry.headers.forEach { (k, v) -> builder.header(k, v) }
    val key = provider.apiKey
    if (key != null) {
      when (entry.auth.type) {
        "bearer" -> builder.header("Authorization", "Bearer " + key)
        "header" -> builder.header(entry.auth.name ?: "x-api-key", key)
        "query", "none" -> {}
        else -> builder.header("Authorization", "Bearer " + key)
      }
    }
    return builder
  }

  private fun withExtras(body: JsonObject, extra: JsonObject?): JsonObject {
    if (extra == null || extra.isEmpty()) return body
    return JsonObject(body + extra)
  }

  private fun streamSse(request: HttpRequest, onData: (String) -> Unit) {
    val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
    response.body().bufferedReader().use { reader ->
      if (response.statusCode() !in 200..299) {
        throw RuntimeException("HTTP " + response.statusCode() + ": " + reader.readText().take(500))
      }
      reader.forEachLine { line ->
        if (cancelled()) throw java.io.InterruptedIOException("остановлено пользователем")
        if (line.startsWith("data:")) onData(line.removePrefix("data:").trim())
      }
    }
  }
}
