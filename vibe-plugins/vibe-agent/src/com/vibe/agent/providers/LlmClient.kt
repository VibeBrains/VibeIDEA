// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.vibe.agent.util.obj
import com.vibe.agent.util.arr
import com.intellij.openapi.diagnostic.logger
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.resilience.ProxySettings
import com.vibe.agent.resilience.RetryPolicy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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

data class ChatMessage(
  val role: String,
  val text: String,
  val images: List<ImagePart> = emptyList(),
  /** Assistant only: its reasoning as streamed; on the wire only for a model that requires it back. */
  val reasoning: String? = null,
  /** Assistant only: the tools it called in this message (the direct chat's tool loop). */
  val toolCalls: List<ToolCall> = emptyList(),
  /** Role [ToolCalls.ROLE] only: the results of one round, answered together. */
  val toolResults: List<ToolResult> = emptyList(),
  /** Assistant only, from the thread history: the tool rounds before this answer; expanded by [ToolRounds.expand] before sending. */
  val toolRounds: List<ToolRound> = emptyList(),
) {
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

  /**
   * openai: "content" is a string, or [{type:text},{type:image_url,image_url:{url:data-url}}…].
   *
   * [echoReasoning] puts an assistant message's reasoning back as `reasoning_content` — only for a
   * model that requires it (`ECHO_REASONING`); a wire that does not expect the field may reject it.
   */
  fun openAi(m: ChatMessage, echoReasoning: Boolean = false): JsonObject = buildJsonObject {
    put("role", m.role)
    if (echoReasoning && m.role == "assistant" && !m.reasoning.isNullOrEmpty()) put("reasoning_content", m.reasoning)
    // An answer that only calls tools has no text; openai wants null there, not an empty string.
    if (m.images.isEmpty()) put("content", if (m.toolCalls.isNotEmpty() && m.text.isBlank()) ToolCalls.NO_CONTENT else JsonPrimitive(m.text))
    else put("content", JsonArray(buildList {
      if (m.text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", m.text) })
      m.images.forEach { img ->
        add(buildJsonObject {
          put("type", "image_url")
          put("image_url", buildJsonObject { put("url", DATA_URL_PREFIX + img.mimeType + DATA_URL_BASE64_MARKER + img.base64) })
        })
      }
    }))
    if (m.toolCalls.isNotEmpty()) put("tool_calls", ToolCalls.openAiCalls(m.toolCalls))
  }

  /** anthropic: "content" is a string, or [{type:image,source:{base64}}…,{type:text}]. */
  /**
   * [cacheable] marks the end of the stable prefix: everything up to and including this message is
   * the same on the next turn, so the provider may bill it as a cache hit. Marked on the message
   * rather than on the whole request because that is where the boundary actually is.
   */
  fun anthropic(m: ChatMessage, cacheable: Boolean = false, ttl: String? = null): JsonObject = when {
    m.role == ToolCalls.ROLE -> ToolCalls.anthropicResults(m)
    m.toolCalls.isNotEmpty() -> ToolCalls.anthropicAssistant(m)
    else -> anthropicPlain(m, cacheable, ttl)
  }

  private fun anthropicPlain(m: ChatMessage, cacheable: Boolean, ttl: String?): JsonObject = buildJsonObject {
    put("role", m.role)
    if (m.images.isEmpty() && !cacheable) put("content", m.text)
    else if (m.images.isEmpty()) put("content", JsonArray(listOf(buildJsonObject {
      put("type", "text")
      put("text", m.text)
      put("cache_control", cacheControl(ttl))
    })))
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
      if (m.text.isNotBlank()) add(buildJsonObject {
        put("type", "text")
        put("text", m.text)
        if (cacheable) put("cache_control", cacheControl(ttl))
      })
    }))
  }

  /** Маркер кэша: `ttl` пишется только когда он есть — вендор по умолчанию даёт пять минут. */
  fun cacheControl(ttl: String?): JsonObject = buildJsonObject {
    put("type", "ephemeral")
    PromptCache.ttlOf(ttl)?.let { put("ttl", it) }
  }

  /** gemini: "parts" is [{text}] plus one {inlineData:{mimeType,data}} per image; assistant role becomes "model". */
  fun gemini(m: ChatMessage): JsonObject = when {
    m.role == ToolCalls.ROLE -> ToolCalls.geminiResults(m)
    m.toolCalls.isNotEmpty() -> ToolCalls.geminiAssistant(m)
    else -> geminiPlain(m)
  }

  private fun geminiPlain(m: ChatMessage): JsonObject = buildJsonObject {
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
class LlmClient(
  private val http: HttpClient = defaultClient(Duration.ofSeconds(20)),
  /**
   * Whose quirk catalogue to apply. Null is not «нет проекта вообще», it is «работа вне проекта» —
   * settings pages and the catalogue probe, which have their own entry in the registry.
   */
  private val projectBase: String? = null,
) {
  private fun quirks(): List<ModelQuirks.Rule> = ModelQuirksRegistry.rulesFor(projectBase)

  /**
   * What the provider said this turn cost, filled in as the stream reports it.
   *
   * Held on the client rather than returned from [chat] because both wires report it in pieces and
   * at different moments: reading it at the end is the only place that sees the whole answer.
   */
  @Volatile
  private var lastUsage: TokenUsage = TokenUsage.NONE

  /** Usage of the last completed request, or [TokenUsage.NONE] when the provider reported none. */
  fun lastUsage(): TokenUsage = lastUsage

  /**
   * Which model the provider said actually answered, filled in as the stream reports it.
   *
   * Kept beside the usage for the same reason: only the end of the turn sees the whole answer.
   */
  @Volatile
  private var lastAnsweredModel: String? = null

  /** The model of the last completed request as the provider named it, or null when it named none. */
  fun lastAnsweredModel(): String? = lastAnsweredModel

  @Volatile private var offeredTools: List<ToolSpec> = emptyList()
  @Volatile private var toolCalls = ToolCallAccumulator()

  /** The tools the model called in the last completed request, in answer order; empty when it called none. */
  fun lastToolCalls(): List<ToolCall> = toolCalls.calls()

  /** False for a model the quirk catalogue marks [ModelQuirks.Quirk.NO_TOOLS]: a request with tools fails as a whole. */
  fun supportsTools(model: ModelEntry): Boolean = !ModelQuirks.has(quirkIdOf(model), ModelQuirks.Quirk.NO_TOOLS, quirks())

  /**
   * Requested id → the snapshot that answered it, learned from replies of this client.
   *
   * Quirks are written for real builds, and a floating alias never contains the build name, so they
   * used to miss behind `~vendor/model-latest`. The first reply teaches the snapshot; every later
   * request of that model is shaped by it. A substitution by another model is not learned (see
   * [ModelEcho.quirkId]).
   */
  private val snapshots = java.util.concurrent.ConcurrentHashMap<String, String>()

  private fun quirkIdOf(model: ModelEntry): String = ModelEcho.quirkId(model.id, snapshots[model.id])

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
    /**
     * Рассуждение модели, если провод его присылает.
     *
     * Отдельным колбэком, а не подмешиванием в ответ: мысль и ответ живут в разных местах ленты,
     * и склеенные они дают текст, который нельзя ни свернуть, ни скопировать как ответ. По
     * умолчанию — в никуда: у большинства вызовов (инлайн-правка, мост в Telegram) места для
     * мысли нет вовсе.
     */
    onThought: (String) -> Unit = {},
    /**
     * Tools the model may call. Empty — the request is exactly what it was before tools existed. The calls
     * are read after the request with [lastToolCalls]; running them is the caller's business.
     */
    tools: List<ToolSpec> = emptyList(),
    onDelta: (String) -> Unit,
  ) {
    this.thought = onThought
    this.cancelled = isCancelled
    lastUsage = TokenUsage.NONE
    lastAnsweredModel = null
    offeredTools = if (tools.isEmpty() || !supportsTools(model)) emptyList() else tools
    toolCalls = ToolCallAccumulator()
    // The offline promise is kept HERE, at the single door out: a check in the UI would be a
    // reminder, and a reminder is not a guarantee. A local provider is still allowed — nothing
    // leaves the machine.
    if (com.vibe.agent.settings.VibeAgentSettings.offline && !provider.isLocal) {
      throw IllegalStateException(t("offline.blocked", "provider" to provider.entry.id))
    }
    var attempt = 1
    while (true) {
      try {
        // A retry starts a new answer: calls half-collected from the failed stream are not calls.
        toolCalls = ToolCallAccumulator()
        // Мысль, приехавшая тегами внутри ответа, снимается ОДИН раз на все три провода: модель,
        // пишущая `<think>`, может стоять на любом из них, и три копии правила разошлись бы.
        // Свой разделитель на попытку: оборванный поток мог остаться внутри незакрытого тега.
        val inline = InlineThinking(onAnswer = onDelta, onThought = onThought)
        // The MODEL decides, falling back to the provider: one key can serve three formats
        // (OpenCode Go: MiniMax and Qwen over /v1/messages, GLM and Kimi over /v1/chat/completions).
        when (ProvidersService.protocolFor(provider.protocol, model.protocol)) {
          "anthropic" -> anthropicChat(provider, model, messages, inline::accept)
          "gemini" -> geminiChat(provider, model, messages, inline::accept)
          else -> openAiChat(provider, model, messages, inline::accept)
        }
        // Придержанный хвост отдаётся здесь: без этого последние символы ответа теряются, когда
        // поток кончился на том, что могло быть началом тега.
        inline.finish()
        lastAnsweredModel?.let { answered ->
          if (ModelEcho.quirkId(model.id, answered) == answered) snapshots[model.id] = answered
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

  /**
   * GET model catalog; openai-style {data:[{id}]} and gemini-style {models:[{name}]} are both understood.
   * Each model comes with what the catalog says it accepts, when it says it ([CatalogModel]).
   */
  fun listModels(provider: ResolvedProvider, fetchUrl: String?): List<CatalogModel> {
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
    // Заодно запоминаем, какое окно провайдер приписывает своим моделям: второй поход в сеть ради
    // одной цифры был бы расточительством, а расхождение с конфигом надо кому-то заметить.
    ClaimedContextRegistry.record(entry.id, ClaimedContext.parse(root))
    return CatalogModel.parse(root)
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
    return json.parseToJsonElement(response.body()).jsonObject["choices"].arr()?.firstOrNull()
      .obj()?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
  }

  private fun geminiChat(provider: ResolvedProvider, model: ModelEntry, messages: List<ChatMessage>, onDelta: (String) -> Unit) {
    val system = messages.filter { it.role == "system" }.joinToString("\n") { it.text }
    val body = withExtras(buildJsonObject {
      if (system.isNotBlank()) put("systemInstruction", buildJsonObject {
        put("parts", JsonArray(listOf(buildJsonObject { put("text", system) })))
      })
      put("contents", JsonArray(messages.filter { it.role != "system" }.map(LlmMessages::gemini)))
      if (offeredTools.isNotEmpty()) put("tools", ToolCalls.geminiTools(offeredTools))
      put("generationConfig", buildJsonObject {
        model.temperature?.let { put("temperature", it) }
        model.topP?.let { put("topP", it) }
        model.topK?.let { put("topK", it) }
        model.maxOutputTokens?.let { put("maxOutputTokens", it) }
      })
    }.let { withReasoning(it, "gemini", model) }, model.extraBody)
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
      val event = eventObject(data) ?: return@streamSse
      ModelEcho.fromGeminiEvent(event)?.let { lastAnsweredModel = it }
      // У Gemini мысль и ответ лежат в одном массиве частей и различаются пометкой `thought`:
      // раньше бралась ПЕРВАЯ часть, то есть при включённых рассуждениях мысль уезжала в ответ.
      ReasoningStream.fromGeminiEvent(event)?.let { thought(it) }
      toolCalls.geminiEvent(event)
      ReasoningStream.answerFromGeminiEvent(event)?.let { onDelta(it) }
    }
  }

  private fun openAiChat(provider: ResolvedProvider, model: ModelEntry, messages: List<ChatMessage>, onDelta: (String) -> Unit) {
    // The quirks catalogue rewrites what this particular model refuses to be asked, and does it
    // BEFORE extraBody so a hand-written entry in providers.json always wins over our guess.
    val overrides = quirks()
    val quirkId = quirkIdOf(model)
    val asked = ModelQuirks.applyToMessages(quirkId, messages, overrides)
    val streaming = ModelQuirks.supportsStreaming(quirkId, overrides)
    val body = withExtras(ModelQuirks.applyToBody(quirkId, overrides = overrides, body = buildJsonObject {
      put("model", model.id)
      put("stream", true)
      // Without this the OpenAI wire streams no usage at all and the turn is billed by guesswork.
      // Servers that do not know the option ignore an unknown field, which is why it is safe to
      // send to every openai-compatible endpoint rather than to a list of known-good ones.
      put("stream_options", buildJsonObject { put("include_usage", true) })
      model.temperature?.let { put("temperature", it) }
      model.topP?.let { put("top_p", it) }
      model.maxOutputTokens?.let { put("max_tokens", it) }
      // A model that requires its reasoning back gets it; no other model ever sees the field.
      val echo = ModelQuirks.has(quirkId, ModelQuirks.Quirk.ECHO_REASONING, overrides)
      // A round's results are one message here and several on this wire.
      put("messages", JsonArray(asked.flatMap {
        if (it.role == ToolCalls.ROLE) ToolCalls.openAiResults(it) else listOf(LlmMessages.openAi(it, echo))
      }))
      if (offeredTools.isNotEmpty()) put("tools", ToolCalls.openAiTools(offeredTools))
    }.let { withReasoning(it, "openai", model) }), model.extraBody)
    if (ModelQuirks.quirksOf(quirkId, overrides).isNotEmpty()) {
      logger<LlmClient>().info("Model quirks applied for " + quirkId + ": " + ModelQuirks.noteOf(quirkId, overrides))
    }
    val request = requestBuilder(provider, "chat/completions")
      .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
      .build()
    if (!streaming) {
      // A model that refuses to stream answers in one piece. Delivering it as a single delta keeps
      // the rest of the chat unaware: waiting silently is bad, but crashing on «stream unsupported»
      // is worse, and that is what happened before the catalogue existed.
      onDelta(sendWhole(request))
      return
    }
    streamSse(request) { data ->
      if (data == "[DONE]") return@streamSse
      val chunk = eventObject(data) ?: return@streamSse
      TokenUsage.fromOpenAiChunk(chunk)?.let { lastUsage = lastUsage.merge(it) }
      ModelEcho.fromOpenAiChunk(chunk)?.let { lastAnsweredModel = it }
      // reasoning_content — как его называют китайские OpenAI-совместимые эндпоинты (DeepSeek, GLM).
      ReasoningStream.fromOpenAiChunk(chunk)?.let { thought(it) }
      toolCalls.openAiChunk(chunk)
      val delta = chunk["choices"].arr()?.firstOrNull()
        .obj()?.get("delta").obj()?.get("content")?.jsonPrimitive?.contentOrNull
      if (delta != null) onDelta(delta)
    }
  }

  private fun anthropicChat(provider: ResolvedProvider, model: ModelEntry, messages: List<ChatMessage>, onDelta: (String) -> Unit) {
    val overrides = quirks()
    val system = messages.filter { it.role == "system" }.joinToString("\n") { it.text }
    val body = withExtras(buildJsonObject {
      put("model", model.id)
      put("stream", true)
      put("max_tokens", model.maxOutputTokens ?: DEFAULT_MAX_OUTPUT_TOKENS)
      model.temperature?.let { put("temperature", it) }
      model.topP?.let { put("top_p", it) }
      model.topK?.let { put("top_k", it) }
      // A big, unchanging preamble is re-sent every turn and billed every turn; marking it as
      // cacheable turns that into a one-off cost. Marked only when it is big enough to matter —
      // a cache entry is itself a write, and writing one for two lines is a loss.
      if (system.isNotBlank()) {
        if (PromptCache.shouldCacheSystem(system)) {
          put("system", JsonArray(listOf(buildJsonObject {
            put("type", "text")
            put("text", system)
            put("cache_control", LlmMessages.cacheControl(model.cacheTtl))
          })))
        }
        else put("system", system)
      }
      // The stable prefix of the conversation is billed once instead of on every turn; the
      // boundary never includes the last message, which is precisely what changed.
      val wire = messages.filter { it.role != "system" }
      val boundary = PromptCache.cacheBoundary(wire)
      put("messages", JsonArray(wire.mapIndexed { index, message ->
        LlmMessages.anthropic(message, cacheable = index == boundary, ttl = model.cacheTtl)
      }))
      if (offeredTools.isNotEmpty()) put("tools", ToolCalls.anthropicTools(offeredTools))
    }.let { withReasoning(it, "anthropic", model) }
      // Quirks were applied on the OpenAI path only, which left the Anthropic-compatible endpoints
      // — where MiniMax and Qwen actually live — sending exactly the fields those models ignore.
      .let { ModelQuirks.applyToBody(quirkIdOf(model), it, overrides, ModelQuirks.WIRE_ANTHROPIC) },
      model.extraBody)
    val request = requestBuilder(provider, "messages")
      .header("anthropic-version", "2023-06-01")
      // Без бета-заголовка вендор молча оставит пять минут — по цене часовой записи.
      .apply { if (PromptCache.needsExtendedBeta(model.cacheTtl)) header("anthropic-beta", PromptCache.EXTENDED_TTL_BETA) }
      .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
      .build()
    streamSse(request) { data ->
      val obj = eventObject(data) ?: return@streamSse
      // Input, cache reads and cache writes arrive at `message_start`; the output count at
      // `message_delta`. One reader for both, because both put it under `usage`.
      TokenUsage.fromAnthropicEvent(obj)?.let { lastUsage = lastUsage.merge(it) }
      ModelEcho.fromAnthropicEvent(obj)?.let { lastAnsweredModel = it }
      // Рассуждение приезжает тем же событием, но другой дельтой: без этой ветки модель молчала
      // ровно столько, сколько думала, и это выглядело как зависание.
      ReasoningStream.fromAnthropicEvent(obj)?.let { thought(it) }
      toolCalls.anthropicEvent(obj)
      if (obj["type"]?.jsonPrimitive?.contentOrNull == "content_block_delta") {
        val text = obj["delta"].obj()?.get("text")?.jsonPrimitive?.contentOrNull
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

  /**
   * Reasoning fields are merged the same way as model extras, and BEFORE them: a model that spells
   * its own thinking field differently must be able to override ours from `extraBody`.
   */
  /** Куда отдавать рассуждение текущего запроса. Сбрасывается на каждый вызов [chat]. */
  private var thought: (String) -> Unit = {}

  private fun withReasoning(body: JsonObject, protocol: String, model: ModelEntry): JsonObject {
    // Ползунок один на приложение, наборы уровней у моделей разные: просимое приводится к тому,
    // что модель объявила принимать. Ничего не объявила — идёт как есть.
    val asked = ReasoningMode.levelOf(com.vibe.agent.settings.VibeAgentSettings.reasoningLevel)
    val level = ReasoningMode.clamp(asked, model.reasoning)
    // Какое написание мышления принимает эта модель — свойство модели, а не наше умолчание:
    // оба написания отвергаются с 400 на «не своих» моделях (ModelQuirks.ADAPTIVE_THINKING).
    val adaptive = ModelQuirks.has(quirkIdOf(model), ModelQuirks.Quirk.ADAPTIVE_THINKING, quirks())
    val fields = ReasoningMode.bodyFields(protocol, level, model.maxOutputTokens ?: DEFAULT_MAX_OUTPUT_TOKENS,
                                             model.reasoning, adaptive)
    return if (fields.isEmpty()) body else JsonObject(body + fields)
  }

  private fun withExtras(body: JsonObject, extra: JsonObject?): JsonObject {
    if (extra == null || extra.isEmpty()) return body
    return JsonObject(body + extra)
  }

  /** One-shot request for a model that refuses to stream; the whole answer comes back as text. */
  private fun sendWhole(request: HttpRequest): String {
    val response = http.send(request, HttpResponse.BodyHandlers.ofString())
    lastRetryAfter = response.headers().firstValue("retry-after").orElse(null)
    if (cancelled()) throw java.io.InterruptedIOException(STOPPED_BY_USER)
    if (response.statusCode() !in 200..299) {
      throw RuntimeException("HTTP " + response.statusCode() + ": " + response.body().take(500))
    }
    val answer = json.parseToJsonElement(response.body()).jsonObject
    ModelEcho.fromOpenAiChunk(answer)?.let { lastAnsweredModel = it }
    answer["choices"].arr()?.firstOrNull().obj()?.get("message").obj()?.let { toolCalls.openAiMessage(it) }
    return answer["choices"].arr()?.firstOrNull()
      .obj()?.get("message").obj()?.get("content")?.jsonPrimitive?.contentOrNull ?: ""
  }

  /**
   * One SSE event as an object, or null when it is not one — and then it is skipped, not thrown on.
   *
   * A stream carries more than the events we read: keep-alives, `[DONE]`, a vendor's own extension, a line that is not
   * JSON at all. Reading each of them as an object ended the whole turn with a stack trace in the feed — and a turn
   * already paid for died on a line nobody needed (caught on MiniMax's Anthropic endpoint 18.09.2026).
   */
  private fun eventObject(data: String): JsonObject? {
    if (data.isEmpty()) return null
    val element = runCatching { json.parseToJsonElement(data) }.getOrNull() ?: return null
    return element as? JsonObject
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
     * Model traffic gets its own proxy, separate from the IDE's: people routinely need one and not
     * the other, and folding them together means either sending corporate traffic through a
     * personal tunnel or losing the tunnel exactly where it was the point.
     *
     * A malformed setting is logged and ignored rather than thrown: a typo in a proxy address must
     * not make the chat unusable, but it must not pass for «прокси работает» either.
     */
    fun defaultClient(timeout: Duration): HttpClient {
      val builder = HttpClient.newBuilder().connectTimeout(timeout)
      applyIdeTrust(builder)
      val spec = runCatching { com.vibe.agent.resilience.ProxySettings.parse(com.vibe.agent.settings.VibeAgentSettings.llmProxyUrl) }
        .getOrElse {
          logger<LlmClient>().warn("LLM proxy setting is malformed and was ignored: ${it.message}")
          null
        }
      // `ProxySelector.of(...)` отправлял бы в прокси ВСЁ, включая локальные модели на этой же
      // машине: заданный прокси ломал Ollama на localhost:11434 и локальный muse-glimmer. Решение
      // о том, идёт ли хост мимо, принимает [ProxySettings.bypasses]; здесь только проводка.
      spec?.let { builder.proxy(BypassingProxySelector(it.toProxy(), System.getenv("NO_PROXY") ?: System.getenv("no_proxy"))) }
      return builder.build()
    }

    /**
     * Доверие и авторизация — те же, что у самой IDE: сертификаты и аутентификатор прокси.
     *
     * Чего не хватало. Клиент собирался с нуля, и потому НЕ знал двух вещей, которые человек уже
     * настроил в IDE: (1) хранилища сертификатов — корпоративный корневой сертификат, добавленный
     * в настройках, на запросы к провайдерам не действовал, и за таким прокси чат падал на
     * рукопожатии TLS; (2) логина и пароля прокси — прокси с авторизацией мы не проходили вовсе.
     * Платформа отдаёт и то, и другое (`PlatformHttpClient`, 2026.3), и это ровно та часть, которую
     * незачем писать самим.
     *
     * МАРШРУТ при этом остаётся НАШ. Трафик моделей ходит своим прокси намеренно — человеку
     * регулярно нужен один туннель и не нужен другой, — поэтому здесь берутся только доверие и
     * авторизация, а выбор прокси делает [BypassingProxySelector] ниже. Аутентификатор без прокси
     * ничего не делает: он срабатывает, лишь когда прокси реально ответил «нужен пароль».
     *
     * Под `runCatching` и с проверкой стадии запуска: до инициализации приложения сервисов ещё
     * нет, а падать из-за украшения клиента нельзя — без него запрос просто пойдёт как раньше.
     */
    private fun applyIdeTrust(builder: HttpClient.Builder) {
      runCatching {
        val app = com.intellij.openapi.application.ApplicationManager.getApplication() ?: return
        if (app.isDisposed) return
        app.getServiceIfCreated(com.intellij.util.net.ssl.CertificateManager::class.java)
          ?.let { builder.sslContext(it.sslContext) }
        builder.authenticator(com.intellij.util.net.JdkProxyProvider.getInstance().authenticator)
      }.onFailure {
        logger<LlmClient>().warn("could not take the IDE trust settings for the model client: ${it.message}")
      }
    }

    /**
     * Выбор прокси по хосту: петля и перечисленное в `NO_PROXY` идут напрямую.
     *
     * Отдельный класс, а не лямбда, потому что `ProxySelector` — абстрактный класс с двумя
     * методами, и второй обязателен. `connectFailed` молчит осознанно: выбор здесь статический,
     * запасного маршрута нет, и «запомнить неудачу» означало бы ровно ничего.
     */
    private class BypassingProxySelector(
      private val proxy: java.net.Proxy,
      private val noProxy: String?,
    ) : java.net.ProxySelector() {
      override fun select(uri: java.net.URI): List<java.net.Proxy> =
        if (ProxySettings.bypasses(uri.host.orEmpty(), noProxy)) listOf(java.net.Proxy.NO_PROXY) else listOf(proxy)

      override fun connectFailed(uri: java.net.URI, address: java.net.SocketAddress, failure: java.io.IOException) = Unit
    }

    /**
     * Client for catalog polling only: the chat client waits 20 s for a connection (a chat is
     * worth waiting for), while a catalog refresh runs behind a served cache and must not.
     */
    fun forCatalog(): LlmClient =
      LlmClient(defaultClient(Duration.ofMillis(CATALOG_TIMEOUT_MS)))

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
