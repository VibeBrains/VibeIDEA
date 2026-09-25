// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The MCP client over HTTP against a real local server shaped like VibeMemory's team host:
 * `POST /mcp` with a Bearer token, one message per request, `initialize` of 2025-06-18, notifications answered 202
 */
class McpHttpTransportTest {
  private data class Seen(val method: String?, val headers: Map<String, String?>)

  private val seen = CopyOnWriteArrayList<Seen>()
  private var status401 = false
  private var streamAnswers = false

  private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
    createContext("/mcp") { exchange -> answer(exchange) }
    start()
  }

  private val url = URI.create("http://127.0.0.1:${server.address.port}/mcp")

  @AfterTest
  fun stop() = server.stop(0)

  private fun answer(exchange: HttpExchange) {
    val body = Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
    val method = body["method"]?.jsonPrimitive?.content
    seen += Seen(method, HEADERS.associateWith { exchange.requestHeaders.getFirst(it) })
    if (status401 || exchange.requestHeaders.getFirst("Authorization") != "Bearer vmt_abc_secret") {
      exchange.sendResponseHeaders(401, -1)
      exchange.close()
      return
    }
    val id = body["id"]?.jsonPrimitive?.content
    if (id == null) {
      exchange.sendResponseHeaders(202, -1)
      exchange.close()
      return
    }
    val result = when (method) {
      "server/discover" -> """"error":{"code":-32601,"message":"method not found"}"""
      "initialize" -> """"result":{"protocolVersion":"2025-06-18","instructions":"team acme"}"""
      "tools/list" -> """"result":{"tools":[{"name":"memory_search","description":"Search"},{"name":"memory_save","description":"Save"}]}"""
      "tools/call" -> """"result":{"content":[{"type":"text","text":"saved in project VibeIDEA"}]}"""
      else -> """"error":{"code":-32601,"message":"method not found"}"""
    }
    val text = """{"jsonrpc":"2.0","id":$id,$result}""".toByteArray()
    exchange.responseHeaders.add("Content-Type", if (streamAnswers) "text/event-stream" else "application/json")
    exchange.sendResponseHeaders(200, text.size.toLong())
    exchange.responseBody.use { it.write(text) }
  }

  private fun client(token: String = "vmt_abc_secret") =
    McpClient(McpHttpTransport(url, mapOf("Authorization" to "Bearer $token"), HttpClient.newHttpClient()))

  @Test
  fun `a server of the previous revision is met with initialize and then answers tools`() {
    val client = client()
    assertEquals("team acme", client.initialize("test", 5_000))
    assertNull(client.revision, "the probe of the new revision was refused, the old handshake took over")
    assertEquals(listOf("memory_search", "memory_save"), client.listTools(5_000).map { it.name })
    val result = client.callTool("memory_save", buildJsonObject { put("project", "VibeIDEA") }, 5_000)
    assertEquals("saved in project VibeIDEA", result.text)

    assertEquals(listOf("server/discover", "initialize", "notifications/initialized", "tools/list", "tools/call"), seen.map { it.method })
    // The agreed revision travels in a header after the handshake, and the method and tool on every request
    assertNull(seen.first { it.method == "initialize" }.headers["MCP-Protocol-Version"])
    assertEquals("2025-06-18", seen.first { it.method == "tools/list" }.headers["MCP-Protocol-Version"])
    assertEquals("tools/call", seen.last().headers["Mcp-Method"])
    assertEquals("memory_save", seen.last().headers["Mcp-Name"])
    assertTrue(seen.all { it.headers["Accept"]!!.contains("application/json") && it.headers["Accept"]!!.contains("text/event-stream") })
  }

  @Test
  fun `a refused token is its own error, raised on the first request rather than hidden by the probe`() {
    assertFailsWith<McpClient.Unauthorized> { client("vmt_abc_wrong").initialize("test", 5_000) }
    assertEquals(listOf("server/discover"), seen.map { it.method }, "the same refused token is not sent a second time")
  }

  @Test
  fun `an answer streamed as SSE is refused by name, not half read`() {
    val client = client()
    client.initialize("test", 5_000)
    streamAnswers = true
    val error = assertFailsWith<McpClient.McpException> { client.listTools(5_000) }
    assertTrue(error.message!!.contains("SSE"), error.message)
  }

  @Test
  fun `only a plain tool name goes into the Mcp-Name header`() {
    fun call(name: String): JsonObject = buildJsonObject {
      put("method", "tools/call")
      put("params", buildJsonObject { put("name", name) })
    }
    assertEquals("memory_save", McpHttpTransport.toolName(call("memory_save")))
    assertNull(McpHttpTransport.toolName(call("память")))
    assertNull(McpHttpTransport.toolName(buildJsonObject { put("method", "tools/list") }))
  }

  private companion object {
    val HEADERS = listOf("Accept", "MCP-Protocol-Version", "Mcp-Method", "Mcp-Name")
  }
}
