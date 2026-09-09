// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import com.vibe.agent.i18n.VibeI18n.t

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal Agent Client Protocol client: JSON-RPC 2.0, newline-delimited JSON over stdio.
 * The agent runs as a local subprocess started WITHOUT a shell (command + args list) —
 * VibeIDE contract. Incoming traffic is data, never instructions for the IDE itself.
 */
class AcpClient(
  private val config: AgentServerConfig,
  private val workingDir: String?,
  private val handler: Handler,
  /** Announce the standard ACP `terminal` capability so non-Claude agents delegate execution to us. */
  private val advertiseTerminalExec: Boolean = false,
) {
  interface Handler {
    fun onSessionUpdate(update: JsonObject)
    /** Agent switched the session mode (`current_mode_update`); called on the reader thread before [onSessionUpdate]. */
    fun onModeChanged(modeId: String) {}
    /** The agent changed its own configuration switches; the list is the whole current set. */
    fun onConfigOptionsChanged(options: List<SessionConfigOption>) {}

    /**
     * Запись MCP-сервера IDE для этой сессии, или null.
     *
     * Спрашивается у клиента, а не решается здесь: доступность зависит от настроек IDE и токена,
     * а этот класс — транспорт, и знать о настройках ему незачем.
     */
    fun ideToolsFor(agentSupportsHttp: Boolean): Map<String, Any>? = null
    /** Called on the reader thread; must return the permission outcome (closed dialog = refusal). */
    fun onRequestPermission(params: JsonObject): JsonElement

    /**
     * `elicitation/create`: агент просит ДАННЫЕ, а не разрешение (ACP, стабилизировано 22.07.2026).
     *
     * Умолчание — вежливый отказ, а не исключение: клиент, который не умеет показать форму, обязан
     * сказать об этом протоколом, иначе агент ждёт ответа, которого не будет.
     */
    fun onElicit(params: JsonObject): JsonElement = Elicitation.response(Elicitation.Outcome.DECLINE)

    /** `elicitation/complete`: URL-режим завершён на стороне агента. Нотификация — ответа не ждут. */
    fun onElicitComplete(params: JsonObject) {}
    fun onReadTextFile(params: JsonObject): JsonElement
    fun onWriteTextFile(params: JsonObject): JsonElement
    // Standard ACP terminal/… (for agents that delegate execution). Default = not supported.
    fun onCreateTerminal(params: JsonObject): JsonElement = throw UnsupportedOperationException("terminal not supported")
    fun onTerminalOutput(params: JsonObject): JsonElement = throw UnsupportedOperationException("terminal not supported")
    /** Blocking: returns once the process exits. Dispatched off the reader thread by [respond]. */
    fun onWaitForTerminalExit(params: JsonObject): JsonElement = throw UnsupportedOperationException("terminal not supported")
    fun onKillTerminal(params: JsonObject): JsonElement = throw UnsupportedOperationException("terminal not supported")
    fun onReleaseTerminal(params: JsonObject): JsonElement = throw UnsupportedOperationException("terminal not supported")
    fun onProtocolLog(line: String)
    /** The process of [client] ended on its own; deliberate [stop] calls do not report. */
    fun onProcessExit(client: AcpClient, code: Int)
  }

  private val json = Json { ignoreUnknownKeys = true }
  private var process: Process? = null
  private var writer: BufferedWriter? = null
  private val nextId = AtomicLong(1)
  private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonElement>>()

  @Volatile var sessionId: String? = null
    private set

  /** Parsed from the `initialize` result; null until initialized or after [stop]. */
  @Volatile var capabilities: AgentCapabilities? = null
    private set

  /** Parsed from the `session/new` result; null when the agent reports no modes or after [stop]. */
  @Volatile var modes: SessionModes? = null
    private set

  /** Set by [stop] before the process is destroyed so the exit thread stays quiet. */
  @Volatile private var stopped = false

  val isAlive: Boolean get() = process?.isAlive == true

  /** Resolution order matches the providers': OS keychain via `.vibe/.env`, then the environment. */
  private fun secrets(value: String): String = com.vibe.agent.security.SecretRefs.substitute(value) { name ->
    com.vibe.agent.providers.ApiKeyResolver.dotEnv(workingDir)[name] ?: System.getenv(name)
  }

  fun start() {
    check(process == null) { "already started" }
    val cmd = ArrayList<String>()
    cmd.add(resolveBinary(config.command))
    cmd.addAll(config.args)
    val pb = ProcessBuilder(cmd)
    workingDir?.let { pb.directory(File(it)) }
    // GUI apps on macOS do not inherit the shell PATH — extend it with well-known dirs.
    val path = (pb.environment()["PATH"] ?: "") + File.pathSeparator + EXTRA_PATH
    pb.environment()["PATH"] = path
    // `${secret:NAME}` in the agent's env is resolved HERE, at start, from the OS keychain and
    // `.vibe/.env` — so the token is not written into `.vibe/acp.json`, which travels with the
    // repository. What this does NOT do is shorten the token's life inside the agent: a child
    // process keeps the environment it was started with, and re-launching the agent per call would
    // throw away the session it exists to hold. The file is the leak we can close; the process
    // lifetime is a limit we state rather than pretend to have fixed.
    val resolvedEnv = config.env.mapValues { (_, value) -> secrets(value) }
    pb.environment().putAll(resolvedEnv)
    val used = config.env.values.flatMap { com.vibe.agent.security.SecretRefs.names(it) }.distinct()
    if (used.isNotEmpty()) handler.onProtocolLog("[acp] secrets injected into the agent environment: " + used.joinToString(","))
    val p = pb.start()
    process = p
    writer = p.outputStream.bufferedWriter()
    Thread({ readLoop(p.inputStream.bufferedReader()) }, "vibe-acp-reader").apply { isDaemon = true }.start()
    Thread({ p.errorStream.bufferedReader().forEachLine { handler.onProtocolLog("[stderr] $it") } }, "vibe-acp-stderr")
      .apply { isDaemon = true }.start()
    Thread({
      val code = p.waitFor()
      // Nobody will answer the in-flight requests any more: fail them so callers unblock.
      failPending("agent process exited (code $code)")
      if (!stopped) handler.onProcessExit(this, code)
    }, "vibe-acp-exit").apply { isDaemon = true }.start()
  }

  fun stop() {
    stopped = true
    process?.destroy()
    process = null
    sessionId = null
    capabilities = null
    modes = null
    // Тумблеры принадлежат сессии: у мёртвого клиента их нет, как нет и режимов.
    configOptions = emptyList()
    failPending("agent stopped")
  }

  private fun failPending(reason: String) {
    // Remove-and-fail each entry so a future put() racing between a snapshot and a clear()
    // is still observed and failed, never orphaned forever incomplete.
    val ids = pending.keys.toList()
    for (id in ids) pending.remove(id)?.completeExceptionally(IllegalStateException(reason))
  }

  /** Одна запись MCP-сервера, собранная снаружи (карта → JSON без ещё одной модели данных). */
  @Suppress("UNCHECKED_CAST")
  private fun toJson(value: Any?): JsonElement = when (value) {
    is Map<*, *> -> buildJsonObject { value.forEach { (k, v) -> put(k.toString(), toJson(v)) } }
    is List<*> -> JsonArray(value.map { toJson(it) })
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    else -> JsonPrimitive(value?.toString().orEmpty())
  }

  /**
   * Что предложить агенту из инструментов IDE — считается ПОСЛЕ `initialize`, потому что зависит
   * от объявленных им возможностей. Null — предлагать нечего или некому.
   */
  private var ideTools: Map<String, Any>? = null

  /**
   * Открыть сессию, ВОЗОБНОВИВ прежнюю, если она известна и агент это умеет.
   *
   * Наши треды переживают перезапуск IDE с первого дня, а сессия внешнего агента — нет: в ленте
   * разговор был, а агент про него не помнил, и человек пересказывал контекст заново (и платил за
   * него заново). `session/resume` стабилизирован в v1 06.07.2026 и закрывает ровно это.
   *
   * Отказ агента возобновлять — не ошибка: сессия могла истечь на его стороне, и это нормальный
   * ответ, а не сбой. Тогда молча открываем новую — потерять историю неприятно, но упасть при
   * старте вместо начала разговора хуже.
   *
   * @param previousSessionId сессия из прошлого запуска, если мы её запомнили.
   */
  fun initializeAndOpenSession(previousSessionId: String? = null): CompletableFuture<String> {
    val init = buildJsonObject {
      put("protocolVersion", PROTOCOL_VERSION)
      put("clientCapabilities", buildJsonObject {
        put("fs", buildJsonObject {
          put("readTextFile", true)
          put("writeTextFile", true)
        })
        // Standard terminal/… execution: only when the user allows agents to run commands via us.
        if (advertiseTerminalExec) put("terminal", true)
        // Elicitation must be ANNOUNCED, not merely handled: by the spec an agent sends
        // `elicitation/create` only to a client that declared it, so a client that implements the
        // dialogs and stays silent here never receives a single request. Both modes are listed
        // because both are implemented — form as a dialog, url as an ask-then-open.
        // An empty object means «supported»; absence means «not».
        // Boolean session config options (stabilised 2026-07-06) are opt-in for v1 clients: an
        // agent offers none of them unless the client says here that it can render them.
        put("session", buildJsonObject {
          put("configOptions", buildJsonObject { put("boolean", buildJsonObject { }) })
        })
        put("elicitation", buildJsonObject {
          put("form", buildJsonObject { })
          put("url", buildJsonObject { })
        })
        // Claude adapter streams Bash output to us via _meta.terminal_output (read-only display, always on).
        put("_meta", buildJsonObject { put("terminal_output", true) })
      })
    }
    return request("initialize", init).thenCompose { initResult ->
      capabilities = parseCapabilities(initResult)
      ideTools = handler.ideToolsFor(capabilities?.mcpHttp == true)
      // Инструменты самой IDE предлагаются агенту, которого IDE и запустила: без этого он
      // работает в проекте, не видя ни графа импортов, ни поиска по корпусу, ни журнала решений.
      // Решение о том, можно ли, принимает [IdeToolsOffer]; здесь только форма запроса.
      val params = buildJsonObject {
        put("cwd", workingDir ?: System.getProperty("user.home"))
        put("mcpServers", JsonArray(ideTools?.let { listOf(toJson(it)) } ?: emptyList()))
      }
      val resumable = previousSessionId?.takeIf { it.isNotBlank() && capabilities?.resumeSession == true }
      if (resumable == null) {
        request("session/new", params)
      } else {
        request("session/resume", JsonObject(params + mapOf("sessionId" to JsonPrimitive(resumable))))
          .exceptionallyCompose {
            handler.onProtocolLog("[acp] session/resume refused, opening a new session: ${it.message}")
            request("session/new", params)
          }
      }
    }.thenApply { result ->
      val obj = result.jsonObject
      // У `session/resume` идентификатор в ответе НЕОБЯЗАТЕЛЕН: агент возобновляет ту сессию,
      // которую попросили, и повторять её номер ему незачем. Требовать поле — значит уронить
      // возобновление на агенте, который всё сделал правильно.
      val id = obj["sessionId"]?.jsonPrimitive?.contentOrNull
        ?: previousSessionId
        ?: error("agent returned no sessionId")
      modes = parseModes(obj)
      configOptions = parseConfigOptions(obj)
      sessionId = id
      id
    }
  }

  fun prompt(text: String): CompletableFuture<JsonElement> = prompt(listOf(ContentBlock.Text(text)))

  fun prompt(blocks: List<ContentBlock>): CompletableFuture<JsonElement> {
    val sid = checkNotNull(sessionId) { "no session" }
    return request("session/prompt", buildJsonObject {
      put("sessionId", sid)
      put("prompt", JsonArray(blocks.map { it.toJson() }))
    })
  }

  /**
   * Boolean configuration options of the session, as the agent last reported them.
   *
   * The agent owns this list: it names the switches, their captions and their current values, and
   * every answer to `session/set_config_option` carries the whole set back. We never keep our own
   * idea of what is on — a switch remembered locally is a switch that lies after the agent
   * changes it for its own reasons.
   */
  @Volatile var configOptions: List<SessionConfigOption> = emptyList()
    private set

  /** Flips one boolean option; the agent answers with the full, current set. */
  fun setConfigOption(configId: String, value: Boolean): CompletableFuture<Unit> {
    val sid = checkNotNull(sessionId) { "no session" }
    return request("session/set_config_option", buildJsonObject {
      put("sessionId", sid)
      put("configId", configId)
      put("type", "boolean")
      put("value", value)
    }).thenApply { result ->
      // Только когда набор ДЕЙСТВИТЕЛЬНО пришёл: агент, ответивший «ок» без поля, не должен
      // выглядеть как агент, отобравший все свои тумблеры.
      val answered = result as? JsonObject
      if (answered?.get("configOptions") != null) configOptions = parseConfigOptions(answered)
      Unit
    }
  }

  /** Switches the session mode; [modes] is updated once the agent acknowledges. */
  fun setMode(modeId: String): CompletableFuture<Unit> {
    val sid = checkNotNull(sessionId) { "no session" }
    return request("session/set_mode", buildJsonObject {
      put("sessionId", sid)
      put("modeId", modeId)
    }).thenApply {
      modes = modes?.copy(currentModeId = modeId)
      Unit
    }
  }

  // Lenient parsing: a missing or malformed field never fails the handshake, it just reads as "unsupported".
  private fun parseCapabilities(initResult: JsonElement): AgentCapabilities {
    val obj = initResult as? JsonObject ?: return AgentCapabilities(image = false, embeddedContext = false)
    val agent = obj["agentCapabilities"] as? JsonObject
    val prompt = agent?.get("promptCapabilities") as? JsonObject
    val mcp = agent?.get("mcpCapabilities") as? JsonObject
    return AgentCapabilities(
      image = prompt?.get("image").booleanOrFalse(),
      embeddedContext = prompt?.get("embeddedContext").booleanOrFalse(),
      mcpHttp = mcp?.get("http").booleanOrFalse(),
      resumeSession = agent?.get("loadSession").booleanOrFalse(),
    )
  }

  private fun parseModes(sessionResult: JsonObject): SessionModes? {
    val modesObj = sessionResult["modes"] as? JsonObject ?: return null
    val current = modesObj["currentModeId"]?.stringOrNull() ?: return null
    val available = (modesObj["availableModes"] as? JsonArray).orEmpty().mapNotNull { entry ->
      val mode = entry as? JsonObject ?: return@mapNotNull null
      val id = mode["id"]?.stringOrNull() ?: return@mapNotNull null
      SessionMode(
        id = id,
        name = mode["name"]?.stringOrNull() ?: id,
        description = mode["description"]?.stringOrNull(),
      )
    }
    return SessionModes(currentModeId = current, available = available)
  }

  /**
   * Boolean options out of a `session/new` result, a `set_config_option` answer or an update.
   *
   * Anything that is not a boolean option is dropped rather than shown as text: the protocol has
   * other types, and a switch drawn for something that is not a switch sets the wrong value.
   */
  private fun parseConfigOptions(source: JsonObject): List<SessionConfigOption> =
    (source["configOptions"] as? JsonArray).orEmpty().mapNotNull { entry ->
      val option = entry as? JsonObject ?: return@mapNotNull null
      val id = option["id"]?.stringOrNull() ?: return@mapNotNull null
      if (option["type"]?.stringOrNull() != CONFIG_TYPE_BOOLEAN) return@mapNotNull null
      val value = (option["value"] as? JsonPrimitive)?.booleanOrNull ?: return@mapNotNull null
      SessionConfigOption(
        id = id,
        name = option["name"]?.stringOrNull() ?: id,
        description = option["description"]?.stringOrNull(),
        value = value,
      )
    }

  private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

  private fun JsonElement?.booleanOrFalse(): Boolean = (this as? JsonPrimitive)?.booleanOrNull ?: false

  fun cancel() {
    val sid = sessionId ?: return
    notify("session/cancel", buildJsonObject { put("sessionId", sid) })
  }

  private fun request(method: String, params: JsonObject): CompletableFuture<JsonElement> {
    val id = nextId.getAndIncrement()
    val future = CompletableFuture<JsonElement>()
    pending[id] = future
    send(buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      put("method", method)
      put("params", params)
    })
    return future
  }

  private fun notify(method: String, params: JsonObject) {
    send(buildJsonObject {
      put("jsonrpc", "2.0")
      put("method", method)
      put("params", params)
    })
  }

  @Synchronized
  private fun send(message: JsonObject) {
    val w = writer ?: return
    try {
      w.write(message.toString())
      w.write("\n")
      w.flush()
    }
    catch (e: java.io.IOException) {
      // A dead pipe must not surface as an IDE error from whichever thread wrote last.
      handler.onProtocolLog(t("acp.log.writeFailed", "reason" to e.message))
      failPending("agent pipe closed: ${e.message}")
    }
  }

  private fun readLoop(reader: BufferedReader) {
    try {
      reader.forEachLine { line ->
        if (line.isBlank()) return@forEachLine
        // A single malformed frame must NEVER kill the reader thread (which would kill the whole
        // ACP connection). Parse and route with safe casts; any per-frame error is logged and skipped.
        try {
          val msg = (json.parseToJsonElement(line) as? JsonObject) ?: run {
            handler.onProtocolLog(t("acp.log.notJson", "line" to line.take(200)))
            return@forEachLine
          }
          val id = (msg["id"] as? JsonPrimitive)?.longOrNull
          val method = (msg["method"] as? JsonPrimitive)?.contentOrNull
          val params = msg["params"] as? JsonObject
          when {
            method != null && id != null -> respond(id, method, params ?: JsonObject(emptyMap()))
            method != null -> when {
              method == "session/update" -> if (params != null) onSessionUpdateNotification(params)
              method == Elicitation.COMPLETE_METHOD -> handler.onElicitComplete(params ?: JsonObject(emptyMap()))
              else -> handler.onProtocolLog(t("acp.log.notification", "method" to method))
            }
            id != null -> {
              val future = pending.remove(id) ?: return@forEachLine
              val error = msg["error"]
              if (error != null && error != JsonNull) future.completeExceptionally(RuntimeException(error.toString()))
              else future.complete(msg["result"] ?: JsonNull)
            }
          }
        }
        catch (e: Exception) {
          handler.onProtocolLog(t("acp.log.frameSkipped", "reason" to e.message))
        }
      }
    }
    catch (e: Exception) {
      handler.onProtocolLog(t("acp.log.readerStopped", "reason" to e.message))
    }
  }

  private fun onSessionUpdateNotification(params: JsonObject) {
    val update = params["update"] as? JsonObject
    if (update?.get("sessionUpdate")?.stringOrNull() == UPDATE_CONFIG_OPTIONS) {
      configOptions = parseConfigOptions(update)
      handler.onConfigOptionsChanged(configOptions)
    }
    if (update?.get("sessionUpdate")?.stringOrNull() == UPDATE_CURRENT_MODE) {
      val modeId = update["currentModeId"]?.stringOrNull()
      if (modeId != null) {
        modes = modes?.copy(currentModeId = modeId)
        handler.onModeChanged(modeId)
      }
    }
    handler.onSessionUpdate(params)
  }

  private fun respond(id: Long, method: String, params: JsonObject) {
    // Methods that block on a modal dialog or a subprocess must run OFF the reader thread, or the
    // whole session/update stream (including live terminal output) stalls behind them.
    when (method) {
      "terminal/wait_for_exit" -> return respondAsync(id, "vibe-acp-terminal-wait") { handler.onWaitForTerminalExit(params) }
      "session/request_permission" -> return respondAsync(id, "vibe-acp-permission") { handler.onRequestPermission(params) }
      // Форма блокирует на модальном диалоге ровно так же, как разрешение, — значит, вне потока чтения.
      Elicitation.METHOD -> return respondAsync(id, "vibe-acp-elicit") { handler.onElicit(params) }
      "terminal/create" -> return respondAsync(id, "vibe-acp-terminal-create") { handler.onCreateTerminal(params) }
      "fs/write_text_file" -> return respondAsync(id, "vibe-acp-fs-write") { handler.onWriteTextFile(params) }
    }
    // Fast, non-blocking methods answer inline.
    val result: JsonElement = try {
      when (method) {
        "fs/read_text_file" -> handler.onReadTextFile(params)
        "terminal/output" -> handler.onTerminalOutput(params)
        "terminal/kill" -> handler.onKillTerminal(params)
        "terminal/release" -> handler.onReleaseTerminal(params)
        else -> {
          sendError(id, -32601, "Method not supported by this client: $method")
          return
        }
      }
    }
    catch (e: Exception) {
      sendError(id, -32603, e.message ?: e.javaClass.simpleName)
      return
    }
    sendResult(id, result)
  }

  /** Run a blocking handler call off the reader thread, then answer the request (result or error). */
  private fun respondAsync(id: Long, threadName: String, producer: () -> JsonElement) {
    Thread({
      try { sendResult(id, producer()) }
      catch (e: Exception) { sendError(id, -32603, e.message ?: e.javaClass.simpleName) }
    }, threadName).apply { isDaemon = true }.start()
  }

  private fun sendResult(id: Long, result: JsonElement) {
    send(buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      put("result", result)
    })
  }

  private fun sendError(id: Long, code: Int, message: String) {
    send(buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      put("error", buildJsonObject {
        put("code", code)
        put("message", message)
      })
    })
  }

  companion object {
    /**
     * Версия ACP, на которой мы разговариваем.
     *
     * Отдельной константой, потому что версия — это ось, а не число в теле одного запроса. У
     * протокола опубликован черновик v2 (20.07.2026), и он ломает почти всё, что мы используем:
     * capabilities сливаются в один объект, режимы сессии исчезают как отдельный API и переезжают
     * в configOptions, `session/load` заменяется на `session/resume`, `tool_call` и его обновление
     * становятся одним upsert, клиентские ФС и терминал убираются. Автоматической конверсии
     * v1↔v2 не планируется — только согласование версии при инициализации.
     *
     * Поэтому v1 остаётся рабочей целью (решение №50), а место, где версия называется, — одно.
     */
    const val PROTOCOL_VERSION = 1

    private const val UPDATE_CURRENT_MODE = "current_mode_update"
    private const val UPDATE_CONFIG_OPTIONS = "config_options_update"
    private const val CONFIG_TYPE_BOOLEAN = "boolean"

    private val EXTRA_PATH: String = listOf(
      System.getProperty("user.home") + "/.local/bin",
      System.getProperty("user.home") + "/.npm-global/bin",
      "/opt/homebrew/bin",
      "/usr/local/bin",
    ).joinToString(File.pathSeparator)

    internal fun resolveBinary(binary: String): String {
      if (com.vibe.agent.util.ExecutableNames.looksLikePath(binary)) return binary
      val dirs = (System.getenv("PATH")?.split(File.pathSeparator).orEmpty()) + EXTRA_PATH.split(File.pathSeparator)
      // Имена с расширением идут первыми: на Windows рядом с `npx.cmd` лежит `npx` — скрипт для
      // Git Bash, который `Files.isExecutable` считает исполняемым, а CreateProcess не запускает
      // («error=193, не является приложением Win32»). Поймано на живой машине.
      for (name in com.vibe.agent.util.ExecutableNames.candidates(binary)) {
        dirs.asSequence().map { Path.of(it, name) }.firstOrNull { Files.isExecutable(it) }?.let { return it.toString() }
      }
      return binary
    }

    /**
     * Есть ли чем запустить такую команду — тем же поиском, каким её потом и запустят.
     *
     * Отдельная функция, а не сравнение `resolveBinary(x) != x` на месте вызова: для абсолютного
     * пути резолвер возвращает его же, и такое сравнение молча объявляло бы отсутствующий файл
     * найденным. Один ответ в одном месте.
     */
    fun isAvailable(binary: String): Boolean {
      if (com.vibe.agent.util.ExecutableNames.looksLikePath(binary)) return Files.isExecutable(Path.of(binary))
      return resolveBinary(binary) != binary
    }
  }
}
