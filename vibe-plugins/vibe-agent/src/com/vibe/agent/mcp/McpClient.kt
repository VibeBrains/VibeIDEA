// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * A client of one MCP server: the protocol over a transport — a process's stdio ([McpStdioTransport]) or HTTP
 * ([McpHttpTransport]).
 *
 * Our own agent needs it where an ACP agent does not: an ACP agent is the MCP client itself and only gets
 * a `session/new` record ([MemoryServerOffer]), while the direct chat has nobody else to speak to the server.
 *
 * Only what the direct chat uses is here — the handshake, `tools/list` and `tools/call`.
 * The protocol lives apart from the transport so that a server reached over HTTP is read by the same rules as one
 * started as a process: two copies of the handshake would part ways on exactly the revision question
 */
class McpClient(private val transport: McpTransport) : AutoCloseable {
  data class RemoteTool(val name: String, val description: String, val inputSchema: JsonObject)

  data class CallResult(val text: String, val isError: Boolean)

  open class McpException(message: String) : RuntimeException(message)

  /** The server refused the credentials: a new request with the same ones cannot succeed */
  class Unauthorized(message: String) : McpException(message)

  private val nextId = AtomicLong(1)

  val isAlive: Boolean get() = transport.isAlive

  private fun request(method: String, params: JsonObject, timeoutMs: Long, meta: JsonObject? = null): JsonObject {
    if (!transport.isAlive) throw McpException("server is not running")
    val id = nextId.getAndIncrement()
    val response = transport.exchange(buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      put("method", method)
      put("params", if (meta == null) params else JsonObject(params + ("_meta" to meta)))
    }, id, timeoutMs)
    val error = response["error"] as? JsonObject
    if (error != null) throw McpException(error["message"]?.jsonPrimitive?.contentOrNull ?: error.toString())
    return response["result"] as? JsonObject ?: JsonObject(emptyMap())
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
    transport.agreed(result["protocolVersion"]?.jsonPrimitive?.contentOrNull ?: McpProtocol.VERSION_2025)
    transport.notify(buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") })
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
   *
   * A refused credential is not an «older revision» answer: it is raised as is
   * Otherwise the caller would get it from `initialize`, a second request with the same refused token
   */
  private fun discover(clientVersion: String, timeoutMs: Long): String? {
    // Потолок пробы СВОЙ и короткий. Сервер прежней эры обязан ответить «метод не найден», но
    // обязан не значит отвечает: сервер, который молча глотает незнакомый метод, иначе добавлял бы
    // полный таймаут к каждому подключению — и это была бы наша плата за его молчание.
    val probeMs = minOf(timeoutMs, PROBE_MS)
    val result = try {
      request("server/discover", buildJsonObject {}, probeMs, meta = requestMeta(clientVersion))
    }
    catch (e: Unauthorized) {
      throw e
    }
    catch (_: Exception) {
      return null
    }
    revision = McpProtocol.VERSION_2026
    transport.agreed(McpProtocol.VERSION_2026)
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

  override fun close() = transport.close()

  companion object {
    /** Сколько ждать ответа на пробу новой ревизии, прежде чем знакомиться по-старому. */
    private const val PROBE_MS = 1_500L

    const val RESULT_COMPLETE = "complete"
    const val RESULT_INPUT_REQUIRED = "input_required"

    // Tool results go back to the model, not to the interface: written in English like the rest of the wire.
    private const val INPUT_REQUIRED_MESSAGE =
      "The MCP server asked for additional input before completing %s (resultType input_required). " +
      "This client cannot answer such requests; the call did not complete."
    private const val UNKNOWN_RESULT_MESSAGE = "The MCP server returned %s with an unknown resultType \"%s\"; the result was not read."

    /** Starts the server process and speaks to it over its stdio ([McpStdioTransport.start]) */
    fun start(command: String, args: List<String>, workingDir: Path?, env: Map<String, String> = emptyMap()): McpClient =
      McpClient(McpStdioTransport.start(command, args, workingDir, env))
  }
}
