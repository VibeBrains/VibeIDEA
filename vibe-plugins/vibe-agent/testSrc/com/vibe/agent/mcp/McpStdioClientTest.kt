// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.providers.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The stdio MCP client against a fake server on a pipe, and the rights of the direct chat's tools. */
class McpStdioClientTest {
  /** A server that answers by a script: method → result JSON, or null to stay silent. */
  private class FakeServer(private val answer: (method: String, params: JsonObject) -> String?) {
    val toClient = PipedOutputStream()
    val clientIn = PipedInputStream(toClient, 1 shl 16)
    val fromClient = PipedInputStream(1 shl 16)
    val clientOut = PipedOutputStream(fromClient)
    val seen = java.util.concurrent.CopyOnWriteArrayList<String>()

    init {
      Thread {
        runCatching {
          fromClient.bufferedReader().forEachLine { line ->
            val msg = Json.parseToJsonElement(line).jsonObject
            val method = msg["method"]!!.jsonPrimitive.content
            seen += method
            val id = msg["id"]?.jsonPrimitive?.longOrNull ?: return@forEachLine
            val result = answer(method, msg["params"]?.jsonObject ?: JsonObject(emptyMap())) ?: return@forEachLine
            synchronized(toClient) {
              toClient.write("{\"jsonrpc\":\"2.0\",\"id\":$id,$result}\n".toByteArray())
              toClient.flush()
            }
          }
        }
      }.apply { isDaemon = true }.start()
    }

    // onClose ends the server's output the way process.destroy() ends a real server's stdout: a pipe whose
    // writer never wrote does not notice the reader's close and would block the client's reader forever.
    fun client() = McpStdioClient(clientIn, clientOut) { runCatching { toClient.close() } }
  }

  private val memoryServer = FakeServer { method, params ->
    when (method) {
      "initialize" -> """"result":{"protocolVersion":"2025-06-18","instructions":"project VibeIDEA"}"""
      "tools/list" ->
        if (params["cursor"] == null) """"result":{"tools":[{"name":"memory_search","description":"Search","inputSchema":{"type":"object"}}],"nextCursor":"p2"}"""
        else """"result":{"tools":[{"name":"memory_save","description":"Save"}]}"""
      "tools/call" ->
        if (params["name"]!!.jsonPrimitive.content == "memory_search") """"result":{"content":[{"type":"text","text":"one record"}]}"""
        else """"error":{"code":-32602,"message":"id is required"}"""
      else -> null
    }
  }

  @Test
  fun `handshake, paged tool list and a call`() {
    val client = memoryServer.client()
    assertEquals("project VibeIDEA", client.initialize("test", 2_000))
    assertEquals(listOf("memory_search", "memory_save"), client.listTools(2_000).map { it.name })
    assertEquals(McpStdioClient.CallResult("one record", false), client.callTool("memory_search", JsonObject(emptyMap()), 2_000))
    assertTrue("notifications/initialized" in memoryServer.seen)
    client.close()
  }

  @Test
  fun `a server error becomes an exception with its message`() {
    val client = memoryServer.client()
    val e = assertFailsWith<McpStdioClient.McpException> { client.callTool("memory_save", JsonObject(emptyMap()), 2_000) }
    assertEquals("id is required", e.message)
    client.close()
  }

  @Test
  fun `a silent server times out instead of hanging the chat`() {
    val client = FakeServer { _, _ -> null }.client()
    assertFailsWith<McpStdioClient.McpException> { client.initialize("test", 200) }
    client.close()
    assertFalse(client.isAlive)
  }

  @Test
  fun `reading runs unasked, writing only when approved`() {
    val tools = DirectChatTools(connect = { memoryServer.client() }, clientVersion = "test", timeoutMs = 2_000)
    assertEquals(listOf("memory_search", "memory_save"), tools.specs().map { it.name })
    val asked = ArrayList<McpProtocol.Risk>()
    val approveNothing = { _: ToolCall, risk: McpProtocol.Risk -> asked += risk; risk == McpProtocol.Risk.READ }

    val search = tools.execute(ToolCall("1", "memory_search", "{}"), approveNothing)
    assertEquals("one record", search.text)
    assertFalse(search.isError)

    val save = tools.execute(ToolCall("2", "memory_save", "{}"), approveNothing)
    assertTrue(save.isError)
    assertEquals(listOf(McpProtocol.Risk.READ, McpProtocol.Risk.WRITE), asked)

    val unknown = tools.execute(ToolCall("3", "rm_rf", "{}"), approveNothing)
    assertTrue(unknown.isError)
    assertEquals(2, asked.size, "a tool the server never listed is not even asked about")
    tools.close()
  }

  @Test
  fun `an unknown tool name counts as writing`() {
    assertEquals(McpProtocol.Risk.READ, DirectChatTools.riskOf("history_search"))
    assertEquals(McpProtocol.Risk.WRITE, DirectChatTools.riskOf("memory_delete"))
    assertEquals(McpProtocol.Risk.WRITE, DirectChatTools.riskOf("memory_export"))
  }

  @Test
  fun `no installed server means no tools, not an error`() {
    assertEquals(emptyList(), DirectChatTools(connect = { null }, clientVersion = "test").specs())
  }
}
