// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * The MCP server itself: one pure function from a JSON-RPC request to a JSON-RPC answer.
 *
 * Pure for the same reason [com.vibe.agent.http.HttpApiPolicy] is: this decides what an outside
 * agent may learn and do on the owner's machine, and a security surface reachable only through a
 * socket ends up untested. Tools arrive as an interface, so the whole protocol is exercised without
 * an IDE, a project or a port.
 *
 * Transport is our existing loopback HTTP API on `POST /mcp` — same process, same token, same
 * refusals. A second server would mean a second lifecycle, a second port and a second place to get
 * authentication wrong.
 *
 * **Two revisions, two degrees of strictness.** A request that names [McpProtocol.VERSION_2026] —
 * in the `MCP-Protocol-Version` header or in `_meta` — is held to that revision's transport
 * contract: the header is required and must agree with `_meta`, `Mcp-Method` and `Mcp-Name` are
 * required, an unknown method is HTTP 404. A request that names no version comes from a client of
 * the handshake revision, which sends its `initialize` without the header by its own spec; the 2026
 * text lets a server treat such a request as the older revision (MAY), and refusing it would make
 * us correct and unusable. Headers that ARE sent are checked against the body in either case:
 * whoever routed the request may have trusted them.
 */
object McpServer {
  /** What actually runs a tool. The production implementation talks to the open project. */
  interface Tools {
    data class Result(val text: String, val isError: Boolean = false)

    fun call(name: String, arguments: JsonObject): Result
  }

  /** A notification carries no id and must produce no body — the difference is on the wire. */
  data class Answer(val body: String?, val httpStatus: Int = HTTP_OK)

  /**
   * The transport headers that mirror the body, exactly as sent; null — not sent.
   *
   * [name] is raw: it may carry the base64 sentinel, and decoding it before the comparison is the
   * server's duty by the spec.
   */
  data class Headers(val protocolVersion: String? = null, val method: String? = null, val name: String? = null)

  private val json = Json { ignoreUnknownKeys = true }

  fun handle(body: String, serverVersion: String, tools: Tools, headers: Headers = Headers()): Answer {
    val request = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                  ?: return badRequest(null, McpProtocol.Error.PARSE, "тело должно быть JSON-объектом")
    val id = request["id"]
    val method = request.string("method")
                 ?: return badRequest(id, McpProtocol.Error.INVALID_REQUEST, "нет поля method")
    val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
    // A notification (no id) is answered by status alone, and the 2026 revision defines no header
    // requirements for it: only what IS sent is checked.
    val isRequest = id != null && id !is JsonNull

    // 1. The revision. An unsupported one is refused first, whichever place named it: that answer
    //    carries the list the client needs to retry, and every later check depends on the revision.
    val metaVersion = metaVersion(request, params)
    val headerVersion = headers.protocolVersion?.trim()?.takeIf { it.isNotEmpty() }
    val requested = headerVersion ?: metaVersion
    if (requested != null && requested !in McpProtocol.SUPPORTED) {
      return Answer(unsupportedVersionBody(id, requested), HTTP_BAD_REQUEST)
    }
    if (headerVersion != null && metaVersion != null && headerVersion != metaVersion) {
      return mismatch(id, "заголовок MCP-Protocol-Version ($headerVersion) не совпадает с версией в _meta ($metaVersion)")
    }
    val modern = requested == McpProtocol.VERSION_2026
    if (modern && isRequest && headerVersion == null) {
      return mismatch(id, "нет заголовка MCP-Protocol-Version: в ревизии ${McpProtocol.VERSION_2026} он обязателен")
    }
    if (modern && isRequest && metaVersion == null) {
      return mismatch(id, "в _meta нет ${McpProtocol.Meta.PROTOCOL_VERSION}, а заголовок называет $headerVersion")
    }

    // 2. The routing headers. A gateway decides by them without reading the body, so a header that
    //    says one thing while the body says another would route one call and execute another.
    headers.method?.let { sent ->
      if (sent != method) return mismatch(id, "заголовок Mcp-Method ($sent) не совпадает с методом тела ($method)")
    }
    if (modern && isRequest && headers.method == null) {
      return mismatch(id, "нет заголовка Mcp-Method: в ревизии ${McpProtocol.VERSION_2026} он обязателен")
    }
    val declaredName = nameOf(method, params)
    if (headers.name != null) {
      val sent = decodeHeaderValue(headers.name)
                 ?: return mismatch(id, "заголовок Mcp-Name не разбирается: ${headers.name}")
      if (declaredName != null && sent != declaredName) {
        return mismatch(id, "заголовок Mcp-Name ($sent) не совпадает с именем в теле ($declaredName)")
      }
    }
    else if (modern && isRequest && declaredName != null) {
      return mismatch(id, "нет заголовка Mcp-Name: для $method в ревизии ${McpProtocol.VERSION_2026} он обязателен")
    }

    // An answer to something that was not asked is a protocol error on our side.
    if (!isRequest) return Answer(null, HTTP_ACCEPTED)

    return when (method) {
      "server/discover" -> Answer(resultBody(id, serverVersion) {
        putJsonArray("supportedVersions") { McpProtocol.SUPPORTED.forEach { add(JsonPrimitive(it)) } }
        putJsonObject("capabilities") { putJsonObject("tools") {} }
      })

      // The handshake of the older revision. A supported version is echoed; any other gets ours
      // instead of an error — lifecycle 2025-06-18: «Otherwise, the server MUST respond with another
      // protocol version it supports». Offered is the handshake revision: a client that opens with
      // `initialize` has no use for the revision that abolished it.
      "initialize" -> {
        val asked = params.string("protocolVersion")
        Answer(resultBody(id, serverVersion) {
          put("protocolVersion", asked?.takeIf { it in McpProtocol.SUPPORTED } ?: McpProtocol.VERSION_2025)
          putJsonObject("capabilities") { putJsonObject("tools") {} }
          putJsonObject("serverInfo") { put("name", McpProtocol.SERVER_NAME); put("version", serverVersion) }
        })
      }

      // «The receiver MUST respond promptly with an empty response» (2025-06-18). Empty literally for
      // that revision; the 2026 one requires `resultType` on every result, this one included.
      "ping" -> Answer(if (modern) resultBody(id, serverVersion) {} else emptyResultBody(id))

      "tools/list" -> Answer(resultBody(id, serverVersion) {
        putJsonArray("tools") {
          McpProtocol.TOOLS.forEach { tool ->
            add(buildJsonObject {
              put("name", tool.name)
              put("title", tool.title)
              put("description", tool.description)
              put("inputSchema", tool.schema)
            })
          }
        }
        put("ttlMs", McpProtocol.LIST_TTL_MS)
        put("cacheScope", "private")
      })

      "tools/call" -> {
        val name = params.string("name")
                   ?: return Answer(errorBody(id, McpProtocol.Error.INVALID_PARAMS, "нет имени инструмента"))
        if (McpProtocol.TOOLS.none { it.name == name }) {
          return Answer(errorBody(id, McpProtocol.Error.INVALID_PARAMS, "неизвестный инструмент: $name"))
        }
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        // A tool that fails is NOT a JSON-RPC error: the call was valid and the model must see what
        // went wrong to decide what to do next. Protocol errors are for malformed requests.
        val result = runCatching { tools.call(name, arguments) }
          .getOrElse { failure ->
            // Отмену платформы ловить нельзя: это не отказ инструмента, а требование прекратить
            // работу, и превращённая в текст ответа она оставляет за собой отменённое вычисление.
            if (failure is com.intellij.openapi.progress.ProcessCanceledException) throw failure
            Tools.Result(failure.message ?: failure.javaClass.simpleName, isError = true)
          }
        Answer(resultBody(id, serverVersion) {
          putJsonArray("content") {
            add(buildJsonObject { put("type", "text"); put("text", result.text) })
          }
          put("isError", result.isError)
        })
      }

      // 404 is what 2026-07-28 prescribes. A client of the older revision reads the error from the
      // body of a 200, and to it a 404 says that the session or the endpoint is gone.
      else -> Answer(errorBody(id, McpProtocol.Error.METHOD_NOT_FOUND, "метод не поддерживается: $method"),
                     if (modern) HTTP_NOT_FOUND else HTTP_OK)
    }
  }

  /**
   * The answer when the IDE side is not there at all.
   *
   * Still JSON-RPC, and still carrying the request id: a client that asked a question deserves an
   * answer in the shape it can parse, not our internal error envelope from another protocol.
   */
  fun unavailable(body: String, reason: String): Answer {
    val id = runCatching { json.parseToJsonElement(body).jsonObject["id"] }.getOrNull()
    return Answer(errorBody(id, McpProtocol.Error.INTERNAL, reason), httpStatus = HTTP_UNAVAILABLE)
  }

  /**
   * An `Mcp-Name` value spelled the way the body spells it, or null when it cannot be read.
   *
   * Names outside header-safe ASCII travel as `=?base64?…?=` with UTF-8 inside, and the spec obliges
   * a client to encode even a plain value that merely looks like the sentinel — so a sentinel-shaped
   * value is always an encoding. A plain value with characters a header value may not hold is
   * malformed, which the spec lists among the validation failures.
   */
  private fun decodeHeaderValue(raw: String): String? {
    val sentinel = raw.length >= BASE64_PREFIX.length + BASE64_SUFFIX.length &&
                   raw.startsWith(BASE64_PREFIX) && raw.endsWith(BASE64_SUFFIX)
    if (!sentinel) return raw.takeIf { value -> value.all { it == '\t' || it in ' '..'~' } }
    val encoded = raw.substring(BASE64_PREFIX.length, raw.length - BASE64_SUFFIX.length)
    val bytes = try { Base64.getDecoder().decode(encoded) } catch (e: IllegalArgumentException) { return null }
    return try {
      StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
    }
    catch (e: CharacterCodingException) {
      null
    }
  }

  /**
   * Имя, которое обязан повторять заголовок `Mcp-Name`, — или null там, где имени нет.
   *
   * Три метода спеки несут имя, и у одного оно называется иначе (`resources/read` адресует `uri`).
   * Для всех прочих методов заголовка быть не должно, и требовать совпадения не с чем: `null`
   * здесь значит «нечего сверять», а не «не совпало».
   */
  private fun nameOf(method: String, params: JsonObject): String? = when (method) {
    "tools/call", "prompts/get" -> params.string("name")
    "resources/read" -> params.string("uri")
    else -> null
  }

  /** The revision a request names in `_meta`: in `params`, where the spec puts it, or at the top level. */
  private fun metaVersion(request: JsonObject, params: JsonObject): String? {
    val meta = (params["_meta"] as? JsonObject) ?: (request["_meta"] as? JsonObject)
    return meta?.string(McpProtocol.Meta.PROTOCOL_VERSION)
  }

  /** A string field, or null — also when the field holds an object: malformed input must not throw. */
  private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

  private fun mismatch(id: JsonElement?, message: String): Answer =
    Answer(errorBody(id, McpProtocol.Error.HEADER_MISMATCH, message), HTTP_BAD_REQUEST)

  private fun badRequest(id: JsonElement?, code: Int, message: String): Answer =
    Answer(errorBody(id, code, message), HTTP_BAD_REQUEST)

  /** The schema makes `data` mandatory on `-32022`: the client retries with one of `supported`. */
  private fun unsupportedVersionBody(id: JsonElement?, requested: String): String =
    errorBody(
      id, McpProtocol.Error.UNSUPPORTED_PROTOCOL_VERSION,
      "версия протокола $requested не поддерживается: ${McpProtocol.SUPPORTED.joinToString()}",
      data = buildJsonObject {
        putJsonArray("supported") { McpProtocol.SUPPORTED.forEach { add(JsonPrimitive(it)) } }
        put("requested", requested)
      },
    )

  private fun resultBody(id: JsonElement?, serverVersion: String, build: JsonObjectBuilder.() -> Unit): String =
    buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id ?: JsonNull)
      putJsonObject("result") {
        build()
        // Required since 2026-07-28; clients of the older revision ignore an unknown field, and a
        // client that does not is broken in a way we cannot fix by omitting it.
        put("resultType", "complete")
        putJsonObject("_meta") {
          putJsonObject(McpProtocol.Meta.SERVER_INFO) {
            put("name", McpProtocol.SERVER_NAME)
            put("version", serverVersion)
          }
        }
      }
    }.toString()

  private fun emptyResultBody(id: JsonElement?): String =
    buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id ?: JsonNull)
      putJsonObject("result") {}
    }.toString()

  private fun errorBody(id: JsonElement?, code: Int, message: String, data: JsonObject? = null): String =
    buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id ?: JsonNull)
      putJsonObject("error") {
        put("code", code)
        put("message", message)
        if (data != null) put("data", data)
      }
    }.toString()

  private const val HTTP_OK = 200
  private const val HTTP_ACCEPTED = 202
  private const val HTTP_BAD_REQUEST = 400
  private const val HTTP_NOT_FOUND = 404
  private const val HTTP_UNAVAILABLE = 503

  private const val BASE64_PREFIX = "=?base64?"
  private const val BASE64_SUFFIX = "?="
}
