// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

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
 */
object McpServer {
  /** What actually runs a tool. The production implementation talks to the open project. */
  interface Tools {
    data class Result(val text: String, val isError: Boolean = false)

    fun call(name: String, arguments: JsonObject): Result
  }

  /** A notification carries no id and must produce no body — the difference is on the wire. */
  data class Answer(val body: String?, val httpStatus: Int = 200)

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * @param mcpMethodHeader the `Mcp-Method` header when the client sent one. The 2026 revision
   *   requires it so gateways can route without reading the body; we refuse a header that
   *   contradicts the body, and accept its absence — the clients that omit it are the ones speaking
   *   the older revision, and refusing them would buy correctness at the price of usefulness.
   */
  fun handle(body: String, serverVersion: String, tools: Tools, mcpMethodHeader: String? = null): Answer {
    val request = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                  ?: return Answer(errorBody(null, McpProtocol.Error.PARSE, "тело должно быть JSON-объектом"))
    val id = request["id"]
    val method = request["method"]?.jsonPrimitive?.contentOrNull
                 ?: return Answer(errorBody(id, McpProtocol.Error.INVALID_REQUEST, "нет поля method"))
    val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())

    if (mcpMethodHeader != null && mcpMethodHeader != method) {
      return Answer(errorBody(id, McpProtocol.Error.HEADER_MISMATCH,
                              "заголовок Mcp-Method ($mcpMethodHeader) не совпадает с методом тела ($method)"))
    }

    requestedVersion(request, params)?.let { version ->
      if (version !in McpProtocol.SUPPORTED) {
        return Answer(errorBody(id, McpProtocol.Error.UNSUPPORTED_PROTOCOL_VERSION,
                                "версия протокола $version не поддерживается: ${McpProtocol.SUPPORTED.joinToString()}"))
      }
    }

    // A notification (no id) gets no answer at all, not an empty one: an answer to something that
    // was not asked is a protocol error on our side.
    if (id == null || id is JsonNull) {
      return Answer(null, httpStatus = 202)
    }

    return when (method) {
      "server/discover" -> Answer(resultBody(id, serverVersion) {
        putJsonArray("protocolVersions") { McpProtocol.SUPPORTED.forEach { add(JsonPrimitive(it)) } }
        putJsonObject("capabilities") { putJsonObject("tools") {} }
        putJsonObject("serverInfo") { put("name", McpProtocol.SERVER_NAME); put("version", serverVersion) }
      })

      // The handshake of the older revision. Answering with the version the client asked for is the
      // whole point of the method; asking for one we do not have was refused above.
      "initialize" -> {
        val asked = params["protocolVersion"]?.jsonPrimitive?.contentOrNull
        Answer(resultBody(id, serverVersion) {
          put("protocolVersion", asked ?: McpProtocol.VERSION_2026)
          putJsonObject("capabilities") { putJsonObject("tools") {} }
          putJsonObject("serverInfo") { put("name", McpProtocol.SERVER_NAME); put("version", serverVersion) }
        })
      }

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
        val name = params["name"]?.jsonPrimitive?.contentOrNull
                   ?: return Answer(errorBody(id, McpProtocol.Error.INVALID_PARAMS, "нет имени инструмента"))
        if (McpProtocol.TOOLS.none { it.name == name }) {
          return Answer(errorBody(id, McpProtocol.Error.INVALID_PARAMS, "неизвестный инструмент: $name"))
        }
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        // A tool that fails is NOT a JSON-RPC error: the call was valid and the model must see what
        // went wrong to decide what to do next. Protocol errors are for malformed requests.
        val result = runCatching { tools.call(name, arguments) }
          .getOrElse { Tools.Result(it.message ?: it.javaClass.simpleName, isError = true) }
        Answer(resultBody(id, serverVersion) {
          putJsonArray("content") {
            add(buildJsonObject { put("type", "text"); put("text", result.text) })
          }
          put("isError", result.isError)
        })
      }

      else -> Answer(errorBody(id, McpProtocol.Error.METHOD_NOT_FOUND, "метод не поддерживается: $method"))
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
    return Answer(errorBody(id, McpProtocol.Error.INTERNAL, reason), httpStatus = 503)
  }

  /** The version travels in `_meta` in 2026 and in `params.protocolVersion` in the handshake. */
  private fun requestedVersion(request: JsonObject, params: JsonObject): String? {
    val meta = (request["_meta"] as? JsonObject) ?: (params["_meta"] as? JsonObject)
    meta?.get(McpProtocol.Meta.PROTOCOL_VERSION)?.jsonPrimitive?.contentOrNull?.let { return it }
    return params["protocolVersion"]?.jsonPrimitive?.contentOrNull
  }

  private fun resultBody(id: JsonElement?, serverVersion: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
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

  private fun errorBody(id: JsonElement?, code: Int, message: String): String =
    buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id ?: JsonNull)
      putJsonObject("error") {
        put("code", code)
        put("message", message)
      }
    }.toString()
}
