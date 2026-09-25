// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * JSON-RPC over HTTP POST, one message per request and one answer per response
 *
 * Written for VibeMemory's team server (`POST …/mcp` with a Bearer token, no sessions, no streams), and kept to what
 * the Streamable HTTP transport requires of any client, so another server of the same shape works too:
 * `Accept` names both JSON and SSE, the agreed revision goes in `MCP-Protocol-Version`, a session id the server hands
 * out is sent back, and the method and tool go in `Mcp-Method` / `Mcp-Name` for servers of the 2026-07-28 revision
 *
 * An answer streamed as SSE is refused by name rather than read: no server we speak to streams, and a half-read
 * stream would be a tool result that silently lost its end
 *
 * @param headers the request headers of the server, `Authorization` included; asked once per connection by the caller
 */
class McpHttpTransport(
  private val url: URI,
  private val headers: Map<String, String>,
  private val http: HttpClient,
) : McpTransport {
  private val json = Json { ignoreUnknownKeys = true }
  @Volatile private var closed = false
  @Volatile private var version: String? = null
  @Volatile private var session: String? = null

  override val isAlive: Boolean get() = !closed

  override fun agreed(version: String) {
    this.version = version
  }

  override fun exchange(message: JsonObject, id: Long, timeoutMs: Long): JsonObject {
    if (closed) throw McpClient.McpException("server is not running")
    val response = post(message, timeoutMs)
    val status = response.statusCode()
    if (status == HTTP_UNAUTHORIZED) throw McpClient.Unauthorized(UNAUTHORIZED)
    if (status !in 200..299) throw McpClient.McpException("HTTP $status")
    response.headers().firstValue(SESSION_HEADER).ifPresent { session = it }
    val type = response.headers().firstValue("Content-Type").orElse("")
    if (type.startsWith(SSE)) throw McpClient.McpException("the server streamed its answer (SSE), which this client does not read")
    return runCatching { json.parseToJsonElement(response.body()).jsonObject }
      .getOrElse { throw McpClient.McpException("the answer is not a JSON-RPC message: ${it.message}") }
  }

  override fun notify(message: JsonObject) {
    if (closed) return
    val status = post(message, NOTIFY_TIMEOUT_MS).statusCode()
    if (status == HTTP_UNAUTHORIZED) throw McpClient.Unauthorized(UNAUTHORIZED)
  }

  private fun post(message: JsonObject, timeoutMs: Long): HttpResponse<String> {
    val builder = HttpRequest.newBuilder(url)
      .timeout(Duration.ofMillis(timeoutMs))
      .header("Content-Type", JSON)
      .header("Accept", "$JSON, $SSE")
    headers.forEach { (name, value) -> builder.header(name, value) }
    version?.let { builder.header(VERSION_HEADER, it) }
    session?.let { builder.header(SESSION_HEADER, it) }
    val method = message["method"]?.jsonPrimitive?.contentOrNull
    method?.let { builder.header(METHOD_HEADER, it) }
    toolName(message)?.let { builder.header(NAME_HEADER, it) }
    val request = builder.POST(HttpRequest.BodyPublishers.ofString(message.toString())).build()
    return try {
      http.send(request, HttpResponse.BodyHandlers.ofString())
    }
    catch (e: java.net.http.HttpTimeoutException) {
      throw McpClient.McpException("$method: no answer in $timeoutMs ms")
    }
    catch (e: java.io.IOException) {
      throw McpClient.McpException("$method: ${e.message ?: e.javaClass.simpleName}")
    }
  }

  override fun close() {
    closed = true
  }

  companion object {
    private const val JSON = "application/json"
    private const val SSE = "text/event-stream"
    private const val HTTP_UNAUTHORIZED = 401
    private const val VERSION_HEADER = "MCP-Protocol-Version"
    private const val SESSION_HEADER = "Mcp-Session-Id"
    private const val METHOD_HEADER = "Mcp-Method"
    private const val NAME_HEADER = "Mcp-Name"

    /** A notification is answered with 202 and nothing else: waiting long for that is waiting for nothing */
    private const val NOTIFY_TIMEOUT_MS = 10_000L

    /** Read by the caller, which names the server and what to do; the wire text stays English like the rest */
    const val UNAUTHORIZED = "the server refused the credentials (HTTP 401)"

    /**
     * The tool a `tools/call` names, for `Mcp-Name`; only a plain name travels as a header value, anything else is
     * left to the body rather than encoded — the servers we speak to name their tools in ASCII
     */
    internal fun toolName(message: JsonObject): String? {
      if (message["method"]?.jsonPrimitive?.contentOrNull != "tools/call") return null
      val name = (message["params"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull ?: return null
      return name.takeIf { it.all { c -> c in '!'..'~' } }
    }

    /** A client that takes the IDE's route and trust: the team server is reached the way the IDE reaches the network */
    fun ideClient(connectTimeout: Duration): HttpClient =
      HttpClient.newBuilder().connectTimeout(connectTimeout)
        .apply { com.vibe.agent.util.IdeHttpTrust.apply(this) }
        .build()
  }
}
