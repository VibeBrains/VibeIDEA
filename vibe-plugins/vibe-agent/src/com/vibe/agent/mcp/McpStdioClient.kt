// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A client of one stdio MCP server: newline-delimited JSON-RPC 2.0 over the process's stdin and stdout.
 *
 * Our own agent needs it where an ACP agent does not: an ACP agent is the MCP client itself and only gets
 * a `session/new` record ([MemoryServerOffer]), while the direct chat has nobody else to speak to the server.
 *
 * Speaks the handshake revision ([McpProtocol.VERSION_2025]): stdio servers in the wild, VibeMemory's
 * included, open with `initialize`. Only what the direct chat uses is here — `tools/list` and
 * `tools/call`; a request from the server (`roots/list`, sampling) is answered «method not found»
 * rather than left hanging, because a server waiting for our answer stops answering us.
 *
 * Streams, not a process, in the constructor: the protocol is testable against a pipe, and [start] is
 * the one place that knows about processes.
 */
class McpStdioClient(private val input: InputStream, private val output: OutputStream, private val onClose: () -> Unit = {}) : AutoCloseable {
  data class RemoteTool(val name: String, val description: String, val inputSchema: JsonObject)

  data class CallResult(val text: String, val isError: Boolean)

  class McpException(message: String) : RuntimeException(message)

  private val json = Json { ignoreUnknownKeys = true }
  private val nextId = AtomicLong(1)
  private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()
  @Volatile private var closed = false

  val isAlive: Boolean get() = !closed

  private val reader = Thread({ readLoop(input) }, "vibe-mcp-stdio").apply { isDaemon = true }

  init {
    reader.start()
  }

  private fun readLoop(input: InputStream) {
    try {
      input.bufferedReader(Charsets.UTF_8).forEachLine { line ->
        if (line.isNotBlank()) runCatching { dispatch(json.parseToJsonElement(line).jsonObject) }
      }
    }
    catch (_: java.io.IOException) {
      // The process went away; close() below fails whatever still waits.
    }
    finally {
      close()
    }
  }

  private fun dispatch(message: JsonObject) {
    val id = message["id"]?.jsonPrimitive?.longOrNull
    if (message["method"] != null) {
      if (id != null) send(buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject {
          put("code", McpProtocol.Error.METHOD_NOT_FOUND)
          put("message", "not supported by this client")
        })
      })
      return
    }
    val waiter = id?.let { pending.remove(it) } ?: return
    val error = message["error"] as? JsonObject
    if (error != null) waiter.completeExceptionally(McpException(error["message"]?.jsonPrimitive?.contentOrNull ?: error.toString()))
    else waiter.complete(message["result"] as? JsonObject ?: JsonObject(emptyMap()))
  }

  private fun send(message: JsonObject) {
    synchronized(output) {
      output.write((message.toString() + "\n").toByteArray(Charsets.UTF_8))
      output.flush()
    }
  }

  private fun request(method: String, params: JsonObject, timeoutMs: Long, meta: JsonObject? = null): JsonObject {
    if (closed) throw McpException("server is not running")
    val id = nextId.getAndIncrement()
    val waiter = CompletableFuture<JsonObject>()
    pending[id] = waiter
    try {
      send(buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        put("params", if (meta == null) params else JsonObject(params + ("_meta" to meta)))
      })
      return waiter.get(timeoutMs, TimeUnit.MILLISECONDS)
    }
    catch (e: java.util.concurrent.ExecutionException) {
      throw e.cause ?: e
    }
    catch (e: java.util.concurrent.TimeoutException) {
      throw McpException("$method: no answer in $timeoutMs ms")
    }
    finally {
      pending.remove(id)
    }
  }

  /**
   * Знакомство с сервером. Возвращает его `instructions`, если он их дал.
   *
   * Сперва `server/discover` ревизии 2026-07-28, и только на отказ — прежний `initialize`.
   * Порядок задан спекой ИМЕННО для stdio: по HTTP клиент откатывается по статусу ответа, а
   * здесь статуса нет, поэтому «двухэровый клиент SHOULD отправить `server/discover` первым»
   * (modelcontextprotocol.io/specification/2026-07-28/basic/transports/stdio, сверено 18.09.2026).
   *
   * Наоборот не работает: в ревизии 2026-07-28 `initialize` удалён совсем, а матрица совместимости
   * помечает пару «клиент прежней эры против сервера новой» как отказ. То есть без этой пробы
   * первый же сервер новой эры просто исчезает вместе со своими инструментами, ничего не сказав.
   */
  fun initialize(clientVersion: String, timeoutMs: Long): String? {
    discover(clientVersion, timeoutMs)?.let { return it }
    val result = request("initialize", buildJsonObject {
      put("protocolVersion", McpProtocol.VERSION_2025)
      put("capabilities", JsonObject(emptyMap()))
      put("clientInfo", buildJsonObject { put("name", McpProtocol.SERVER_NAME); put("version", clientVersion) })
    }, timeoutMs)
    send(buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") })
    return result["instructions"]?.jsonPrimitive?.contentOrNull
  }

  /** Эра сервера, с которой мы договорились: пусто, пока знакомство не состоялось. */
  @Volatile var revision: String? = null
    private set

  /**
   * Проба новой ревизии: `server/discover` без параметров.
   *
   * Сервер прежней эры отвечает «метод не найден» (-32601) — это не ошибка, а ответ, и на него мы
   * молча идём знакомиться по-старому. Ответ новой эры приносит `instructions` и список версий;
   * инструкции возвращаются пустой строкой, если сервер их не дал, — иначе вызывающий не отличит
   * «сервер новой эры промолчал» от «пробы не было».
   */
  private fun discover(clientVersion: String, timeoutMs: Long): String? {
    // Потолок пробы СВОЙ и короткий. Сервер прежней эры обязан ответить «метод не найден», но
    // обязан не значит отвечает: сервер, который молча глотает незнакомый метод, иначе добавлял бы
    // полный таймаут к каждому подключению — и это была бы наша плата за его молчание.
    val probeMs = minOf(timeoutMs, PROBE_MS)
    val result = runCatching {
      request("server/discover", buildJsonObject {}, probeMs, meta = requestMeta(clientVersion))
    }.getOrElse { return null }
    revision = McpProtocol.VERSION_2026
    return result["instructions"]?.jsonPrimitive?.contentOrNull.orEmpty()
  }

  /**
   * `_meta` каждого запроса новой ревизии.
   *
   * Два поля обязательны — версия протокола и возможности клиента; возможностей у нас нет, и пустой
   * объект здесь означает именно это: сервер по спеке не вправе просить у нас того, чего мы не
   * объявили (modelcontextprotocol.io/specification/2026-07-28/basic/index).
   */
  private fun requestMeta(clientVersion: String): JsonObject = buildJsonObject {
    put(McpProtocol.Meta.PROTOCOL_VERSION, McpProtocol.VERSION_2026)
    put(McpProtocol.Meta.CLIENT_CAPABILITIES, JsonObject(emptyMap()))
    put(McpProtocol.Meta.CLIENT_INFO, buildJsonObject {
      put("name", McpProtocol.SERVER_NAME)
      put("version", clientVersion)
    })
  }

  /** Every tool, following `nextCursor` until the server has no more pages. */
  fun listTools(timeoutMs: Long): List<RemoteTool> {
    val tools = ArrayList<RemoteTool>()
    var cursor: String? = null
    do {
      val result = request("tools/list", buildJsonObject { cursor?.let { put("cursor", it) } }, timeoutMs)
      (result["tools"] as? JsonArray)?.forEach { element ->
        val tool = element as? JsonObject ?: return@forEach
        val name = tool["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
        tools += RemoteTool(
          name = name,
          description = tool["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
          inputSchema = tool["inputSchema"] as? JsonObject ?: buildJsonObject { put("type", "object") },
        )
      }
      cursor = result["nextCursor"]?.jsonPrimitive?.contentOrNull
    } while (cursor != null)
    return tools
  }

  /**
   * One call; the text parts of the result joined, other parts named by type so nothing vanishes silently.
   *
   * A result of the 2026-07-28 revision says what it is in `resultType`. Only a complete one carries the tool's output:
   * `input_required` asks the client for input and to call again (MRTR), which the direct chat cannot do, and an unknown
   * type is a shape we cannot read. Both come back as an error that names them — read as content they would be an empty
   * success, and the model would take «nothing found» for the answer. No `resultType` is the older revision: complete.
   */
  fun callTool(name: String, arguments: JsonObject, timeoutMs: Long): CallResult {
    val result = request("tools/call", buildJsonObject {
      put("name", name)
      put("arguments", arguments)
    }, timeoutMs)
    when (val type = result["resultType"]?.jsonPrimitive?.contentOrNull) {
      null, RESULT_COMPLETE -> {}
      RESULT_INPUT_REQUIRED -> return CallResult(INPUT_REQUIRED_MESSAGE.format(name), isError = true)
      else -> return CallResult(UNKNOWN_RESULT_MESSAGE.format(name, type), isError = true)
    }
    val text = (result["content"] as? JsonArray).orEmpty().joinToString("\n") { part ->
      val obj = part as? JsonObject
      when (val type = obj?.get("type")?.jsonPrimitive?.contentOrNull) {
        "text" -> obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        else -> "[$type]"
      }
    }
    return CallResult(text, result["isError"]?.jsonPrimitive?.booleanOrNull == true)
  }

  override fun close() {
    if (closed) return
    closed = true
    pending.values.forEach { it.completeExceptionally(McpException("server stopped")) }
    pending.clear()
    runCatching { output.close() }
    onClose()
    // The reader blocks in read(): closing the input is what wakes it. Without this a stopped server left a
    // thread per chat panel waiting on a stream nobody would ever write to again (caught by the leak check).
    runCatching { input.close() }
    if (Thread.currentThread() !== reader) reader.join(READER_JOIN_MS)
  }

  companion object {
    /** A piped stream notices its close within a second; waiting a little longer covers a slow machine. */
    private const val READER_JOIN_MS = 2_000L

    /** Сколько ждать ответа на пробу новой ревизии, прежде чем знакомиться по-старому. */
    private const val PROBE_MS = 1_500L

    const val RESULT_COMPLETE = "complete"
    const val RESULT_INPUT_REQUIRED = "input_required"

    // Tool results go back to the model, not to the interface: written in English like the rest of the wire.
    private const val INPUT_REQUIRED_MESSAGE =
      "The MCP server asked for additional input before completing %s (resultType input_required). " +
      "This client cannot answer such requests; the call did not complete."
    private const val UNKNOWN_RESULT_MESSAGE = "The MCP server returned %s with an unknown resultType \"%s\"; the result was not read."

    /** Starts the server process; its stderr is discarded — it is a log, not a protocol. */
    fun start(command: String, args: List<String>, workingDir: Path?): McpStdioClient {
      val process = ProcessBuilder(listOf(command) + args)
        .apply { workingDir?.let { directory(it.toFile()) } }
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
      return McpStdioClient(process.inputStream, process.outputStream) { process.destroy() }
    }
  }
}
