// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.http.HttpApiPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Asks our own MCP endpoint, over the real socket, what a client of the 2026 revision asks first,
 * and holds the answers to that revision.
 *
 * [McpServer] and [HttpApiPolicy] are pure and tested as such; neither suite sees the listener in
 * between. A header the listener forgot to pass along is invisible to both and obvious to every
 * client — so the doctor asks the real thing, on the owner's machine.
 *
 * Two questions. `server/discover` must answer 200 and list [McpProtocol.VERSION_2026] among
 * `supportedVersions`. A request whose `Mcp-Method` contradicts its body must be refused with 400
 * and `-32020`; that one runs nothing — the refusal precedes any dispatch, and the body asks only
 * for the catalogue anyway.
 */
object McpSelfProbe {
  enum class Check { DISCOVER, HEADER_MISMATCH }

  sealed interface Result {
    data object Ok : Result

    /** No API token is issued: no client gets in, the IDE's own agent included. */
    data object NoToken : Result

    /** Nothing answered; [reason] is the transport's own wording. */
    data class Unreachable(val reason: String) : Result

    /** An answer came and it is not the revision's; [code] is its JSON-RPC error code, if it had one. */
    data class WrongAnswer(val check: Check, val status: Int, val code: Int?) : Result
  }

  /**
   * Long enough for a loopback round trip on a busy machine, short enough for a diagnostic that
   * must not hang: an endpoint that needs longer than this to say «hello» is itself the finding.
   */
  private val TIMEOUT: Duration = Duration.ofSeconds(3)

  private const val HTTP_OK = 200
  private const val HTTP_BAD_REQUEST = 400
  private const val PROBE_ID = "vibe-doctor"
  private const val CLIENT_NAME = "vibeidea-doctor"
  private const val CLIENT_VERSION = "1"

  private val json = Json { ignoreUnknownKeys = true }

  fun run(port: Int, token: String?, timeout: Duration = TIMEOUT): Result {
    if (token.isNullOrEmpty()) return Result.NoToken
    val client = HttpClient.newBuilder()
      // HTTP/1.1 explicitly: the default would offer an h2c upgrade the listener does not speak.
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(timeout)
      .build()
    return try {
      val discover = send(client, port, token, timeout, method = "server/discover", headerMethod = "server/discover")
      checkDiscover(discover.statusCode(), discover.body())?.let { return it }
      val contradicted = send(client, port, token, timeout, method = "tools/list", headerMethod = "tools/call")
      checkMismatch(contradicted.statusCode(), contradicted.body()) ?: Result.Ok
    }
    catch (e: IOException) {
      Result.Unreachable(e.message ?: e.javaClass.simpleName)
    }
  }

  /** Null when the discovery answer is the revision's; otherwise the verdict. */
  internal fun checkDiscover(status: Int, body: String): Result.WrongAnswer? {
    val versions = (parse(body)?.get("result") as? JsonObject)?.get("supportedVersions") as? JsonArray
    val named = versions?.any { (it as? JsonPrimitive)?.contentOrNull == McpProtocol.VERSION_2026 } == true
    return if (status == HTTP_OK && named) null else Result.WrongAnswer(Check.DISCOVER, status, errorCode(body))
  }

  /** Null when the contradicting header was refused the way the revision prescribes. */
  internal fun checkMismatch(status: Int, body: String): Result.WrongAnswer? {
    val code = errorCode(body)
    return if (status == HTTP_BAD_REQUEST && code == McpProtocol.Error.HEADER_MISMATCH) null
           else Result.WrongAnswer(Check.HEADER_MISMATCH, status, code)
  }

  private fun parse(body: String): JsonObject? = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

  private fun errorCode(body: String): Int? =
    ((parse(body)?.get("error") as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull

  private fun send(
    client: HttpClient,
    port: Int,
    token: String,
    timeout: Duration,
    method: String,
    headerMethod: String,
  ): HttpResponse<String> {
    val body = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", PROBE_ID)
      put("method", method)
      putJsonObject("params") {
        putJsonObject("_meta") {
          put(McpProtocol.Meta.PROTOCOL_VERSION, McpProtocol.VERSION_2026)
          putJsonObject(McpProtocol.Meta.CLIENT_INFO) { put("name", CLIENT_NAME); put("version", CLIENT_VERSION) }
          putJsonObject(McpProtocol.Meta.CLIENT_CAPABILITIES) {}
        }
      }
    }
    val request = HttpRequest.newBuilder(endpoint(port))
      .timeout(timeout)
      .header("Authorization", "Bearer $token")
      .header("Content-Type", "application/json")
      .header("Accept", "application/json, text/event-stream")
      .header(McpProtocol.Header.PROTOCOL_VERSION, McpProtocol.VERSION_2026)
      .header(McpProtocol.Header.METHOD, headerMethod)
      .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
      .build()
    return client.send(request, HttpResponse.BodyHandlers.ofString())
  }

  /** The address the listener binds: the loopback, in whichever family the JVM prefers. */
  private fun endpoint(port: Int): URI {
    val loopback = InetAddress.getLoopbackAddress()
    val host = if (loopback is Inet6Address) "[${loopback.hostAddress}]" else loopback.hostAddress
    return URI("http://$host:$port${HttpApiPolicy.PATH_MCP}")
  }
}
