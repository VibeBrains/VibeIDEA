// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.resilience.RetryPolicy

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

/** One inline image: raw base64 payload (no data: prefix) plus its MIME type. */
data class ImagePart(val mimeType: String, val base64: String)

data class ChatMessage(val role: String, val text: String, val images: List<ImagePart> = emptyList()) {
  /** Text-only copy for models without vision; the dropped images are named so the model knows context went missing. */
  fun withoutImages(): ChatMessage =
    if (images.isEmpty()) this
    else copy(text = (text + "\n[${images.size} image(s) omitted: model has no vision]").trim(), images = emptyList())
}

/**
 * Per-protocol message serialization, kept free of transport so it is unit-testable.
 * A message without images keeps the plain-string wire shape; images switch the
 * content to the vendor's multipart form. A blank text next to images is dropped:
 * Anthropic and Gemini reject empty text blocks, and it carries nothing anyway.
 */
internal object LlmMessages {
  private const val DATA_URL_PREFIX = "data:"
  private const val DATA_URL_BASE64_MARKER = ";base64,"

  /** openai: "content" is a string, or [{type:text},{type:image_url,image_url:{url:data-url}}…]. */
  fun openAi(m: ChatMessage): JsonObject = buildJsonObject {
    put("role", m.role)
    if (m.images.isEmpty()) put("content", m.text)
    else put("content", JsonArray(buildList {
      if (m.text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", m.text) })
      m.images.forEach { img ->
        add(buildJsonObject {
          put("type", "image_url")
          put("image_url", buildJsonObject { put("url", DATA_URL_PREFIX + img.mimeType + DATA_URL_BASE64_MARKER + img.base64) })
        })
      }
    }))
  }

  /** anthropic: "content" is a string, or [{type:image,source:{base64}}…,{type:text}]. */
  fun anthropic(m: ChatMessage): JsonObject = buildJsonObject {
    put("role", m.role)
    if (m.images.isEmpty()) put("content", m.text)
    else put("content", JsonArray(buildList {
      m.images.forEach { img ->
        add(buildJsonObject {
          put("type", "image")
          put("source", buildJsonObject {
            put("type", "base64")
            put("media_type", img.mimeType)
            put("data", img.base64)
          })
        })
      }
      if (m.text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", m.text) })
    }))
  }

  /** gemini: "parts" is [{text}] plus one {inlineData:{mimeType,data}} per image; assistant role becomes "model". */
  fun gemini(m: ChatMessage): JsonObject = buildJsonObject {
    put("role", if (m.role == "assistant") "model" else "user")
    put("parts", JsonArray(buildList {
      if (m.images.isEmpty() || m.text.isNotBlank()) add(buildJsonObject { put("text", m.text) })
      m.images.forEach { img ->
        add(buildJsonObject {
          put("inlineData", buildJsonObject { put("mimeType", img.mimeType); put("data", img.base64) })
        })
      }
    }))
  }
}

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
  @Volatile private var activeBody: java.io.InputStream? = null

  /** `Retry-After` of the last response, if the provider sent one. */
  @Volatile private var lastRetryAfter: String? = null

  /** Aborts the in-flight stream from any thread: closing the body wakes a read blocked on a silent server. */
  fun cancel() {
    activeBody?.let { runCatching { it.close() } }
  }

  /** Blocking call; invoke from a pooled thread. onDelta receives text chunks as they stream. */
  fun chat(
    provider: ResolvedProvider,
    model: ModelEntry,
    messages: List<ChatMessage>,
    isCancelled: () -> Boolean = { false },
    /** Told when a wait starts, so the chat can say «жду провайдера» instead of looking frozen. */
    onWaiting: (attempt: Int, delayMs: Long, reason: String?) -> Unit = { _, _, _ -> },
    onDelta: (String) -> Unit,
  ) {
    this.cancelled = isCancelled
    var attempt = 1
    while (true) {
      try {
        when (provider.protocol) {
          "anthropic" -> anthropicChat(provider, model, messages, onDelta)
          "gemini" -> geminiChat(provider, model, messages, onDelta)
          else -> openAiChat(provider, model, messages, onDelta)
        }
        return
      }
      catch (e: Exception) {
        // A rate limit is a queue, not an error: the provider said «через тридцать секунд», and
        // turning that into a red line throws away a turn the user already paid to compose.
        val kind = RetryPolicy.classify(RetryPolicy.statusFromMessage(e.message), e)
        if (cancelled() || !RetryPolicy.shouldRetry(kind, attempt)) throw e
        // Anything already streamed stays on screen; the retry appends to it rather than replacing
        // it, which is honest — those tokens were produced and paid for.
        val delay = RetryPolicy.delayMs(attempt, kind, RetryPolicy.retryAfterSeconds(lastRetryAfter))
        onWaiting(attempt, delay, e.message?.take(200))
        val slept = sleepInterruptibly(delay)
        if (!slept) throw e
        attempt++
      }
    }
  }

  /** Sleeps in short steps so a stop pressed during a wait is honoured immediately. */
  private fun sleepInterruptibly(delayMs: Long): Boolean {
    var left = delayMs
    while (left > 0) {
      if (cancelled()) return false
      val step = minOf(left, SLEEP_STEP_MS)
      Thread.sleep(step)
      left -= step
    }
    return !cancelled()
  }

  /** GET model catalog; openai-style {data:[{id}]} and gemini-style {models:[{name}]} are both understood. */
  fun listModels(provider: ResolvedProvider, fetchUrl: String?): List<String> {
    val entry = provider.entry
    var url = if (!fetchUrl.isNullOrBlank()) fetchUrl else provider.baseUrl.trimEnd('/') + "/models"
    // Same auth/header/query treatment as chat requests — the catalog endpoint is not special.
    val queryParams = LinkedHashMap(entry.query)
    if (entry.auth.type == "query" && entry.auth.name != null && provider.apiKey != null) {
      queryParams[entry.auth.name] = provider.apiKey
    }
    if (queryParams.isNotEmpty()) {
      url += (if ('?' in url) "&" else "?") + queryParams.entries.joinToString("&") {
        URLEncoder.encode(it.key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(it.value, StandardCharsets.UTF_8)
      }
    }
    val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(CATALOG_TIMEOUT_MS)).GET()
    entry.headers.forEach { (k, v) -> builder.header(k, v) }
    // Anthropic rejects any request without the version header, /v1/models included.
    if (provider.protocol == "anthropic") builder.header("anthropic-version", "2023-06-01")
    provider.apiKey?.let { key ->
      when (entry.auth.type) {
        "header" -> builder.header(entry.auth.name ?: "x-api-key", key)
        "query", "none" -> {}
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
      put("max_tokens", if (provider.isLocal) FIM_MAX_TOKENS_LOCAL else FIM_MAX_TOKENS_CLOUD)
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
      put("contents", JsonArray(messages.filter { it.role != "system" }.map(LlmMessages::gemini)))
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
      .timeout(Duration.ofMillis(provider.entry.timeoutMs ?: DEFAULT_REQUEST_TIMEOUT_MS))
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
      put("messages", JsonArray(messages.map(LlmMessages::openAi)))
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
      put("max_tokens", model.maxOutputTokens ?: DEFAULT_MAX_OUTPUT_TOKENS)
      model.temperature?.let { put("temperature", it) }
      model.topP?.let { put("top_p", it) }
      model.topK?.let { put("top_k", it) }
      if (system.isNotBlank()) put("system", system)
      put("messages", JsonArray(messages.filter { it.role != "system" }.map(LlmMessages::anthropic)))
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
      .timeout(Duration.ofMillis(entry.timeoutMs ?: DEFAULT_REQUEST_TIMEOUT_MS))
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
    // The provider knows its own window; guessing shorter means being refused again.
    lastRetryAfter = response.headers().firstValue("retry-after").orElse(null)
    val body = response.body()
    activeBody = body
    try {
      // Stop may have been pressed while we waited for the headers.
      if (cancelled()) throw java.io.InterruptedIOException(STOPPED_BY_USER)
      body.bufferedReader().use { reader ->
        if (response.statusCode() !in 200..299) {
          throw RuntimeException("HTTP " + response.statusCode() + ": " + reader.readText().take(500))
        }
        reader.forEachLine { line ->
          if (cancelled()) throw java.io.InterruptedIOException(STOPPED_BY_USER)
          if (line.startsWith("data:")) onData(line.removePrefix("data:").trim())
        }
      }
    }
    catch (e: java.io.IOException) {
      // close() from cancel() surfaces as IOException("closed") — report it as a user stop, not an error.
      if (cancelled()) throw java.io.InterruptedIOException(STOPPED_BY_USER) else throw e
    }
    finally {
      activeBody = null
    }
  }

  internal companion object {
    /** Waking up this often makes a stop during a wait feel immediate without busy-waiting. */
    const val SLEEP_STEP_MS = 250L

    /**
     * Client for catalog polling only: the chat client waits 20 s for a connection (a chat is
     * worth waiting for), while a catalog refresh runs behind a served cache and must not.
     */
    fun forCatalog(): LlmClient =
      LlmClient(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(CATALOG_TIMEOUT_MS)).build())

    val STOPPED_BY_USER: String get() = t("common.stoppedByUser")
    /** Default per-request timeout when a provider does not set `timeoutMs` (10 min). */
    const val DEFAULT_REQUEST_TIMEOUT_MS = 600_000L
    /** Model catalog timeout: short on purpose — a cached catalog is served meanwhile, so a
     *  silent endpoint must not hold the refresh for half a minute. */
    const val CATALOG_TIMEOUT_MS = 10_000L
    /** FIM completion budget, VibeIDE parity: local models are latency-bound, cloud can afford more. */
    const val FIM_MAX_TOKENS_LOCAL = 96
    const val FIM_MAX_TOKENS_CLOUD = 300
    /** Anthropic requires max_tokens; used when the model entry does not set maxOutputTokens. */
    const val DEFAULT_MAX_OUTPUT_TOKENS = 8192
  }
}
