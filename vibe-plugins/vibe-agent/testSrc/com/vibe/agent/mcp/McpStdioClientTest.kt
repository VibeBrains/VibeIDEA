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
        when (params["name"]!!.jsonPrimitive.content) {
          "memory_search" -> """"result":{"content":[{"type":"text","text":"one record"}]}"""
          "memory_new" -> """"result":{"resultType":"complete","content":[{"type":"text","text":"saved"}]}"""
          "memory_ask" -> """"result":{"resultType":"input_required","inputRequests":{"q":{"method":"elicitation/create"}},"requestState":"s1"}"""
          "memory_future" -> """"result":{"resultType":"deferred"}"""
          else -> """"error":{"code":-32602,"message":"id is required"}"""
        }
      else -> null
    }
  }

  /** Сервер новой эры: `initialize` у него удалён, знакомство идёт через `server/discover`. */
  private val modernServer = FakeServer { method, _ ->
    when (method) {
      "server/discover" ->
        """"result":{"supportedVersions":["2026-07-28"],"capabilities":{},"instructions":"project VibeIDEA","ttlMs":60000}"""
      "initialize" -> """"error":{"code":-32601,"message":"initialize was removed in 2026-07-28"}"""
      else -> null
    }
  }

  /** Сервер прежней эры, отвечающий на незнакомый метод как положено — «метод не найден». */
  private val legacyServer = FakeServer { method, _ ->
    when (method) {
      "server/discover" -> """"error":{"code":-32601,"message":"Method not found"}"""
      "initialize" -> """"result":{"protocolVersion":"2025-06-18","instructions":"project VibeIDEA"}"""
      else -> null
    }
  }

  /** Ждёт, пока фейковый сервер увидит метод: он читает свой конец трубы в своём потоке. */
  private fun awaitSeen(server: FakeServer, method: String, timeoutMs: Long = 2_000): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (method in server.seen) return true
      Thread.sleep(10)
    }
    return false
  }

  @Test
  fun `a modern server is met through server discover, and initialize is never sent`() {
    // Без этой пробы сервер новой эры отвечал бы на initialize отказом, и его инструменты просто
    // исчезали бы вместе с ним — молча. Спека требует пробу ИМЕННО на stdio: статуса, по которому
    // можно откатиться, здесь нет.
    val client = modernServer.client()
    assertEquals("project VibeIDEA", client.initialize("test", 2_000))
    assertEquals("2026-07-28", client.revision)
    assertTrue("server/discover" in modernServer.seen)
    assertFalse("initialize" in modernServer.seen)
    client.close()
  }

  @Test
  fun `a legacy server answers method not found, and the client quietly falls back`() {
    val client = legacyServer.client()
    assertEquals("project VibeIDEA", client.initialize("test", 2_000))
    // Порядок — вот что проверяется: проба, потом прежнее знакомство. Уведомление о готовности
    // уходит следом и считается сервером асинхронно, поэтому его ждём, а не ловим мгновением.
    assertEquals(listOf("server/discover", "initialize"), legacyServer.seen.take(2))
    assertTrue(awaitSeen(legacyServer, "notifications/initialized"), legacyServer.seen.toString())
    assertEquals(null, client.revision)
    client.close()
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
  fun `a result that is not complete is an error that names it, not an empty success`() {
    val client = memoryServer.client()
    client.initialize("test", 2_000)
    assertEquals(McpStdioClient.CallResult("saved", false), client.callTool("memory_new", JsonObject(emptyMap()), 2_000))
    val asked = client.callTool("memory_ask", JsonObject(emptyMap()), 2_000)
    assertTrue(asked.isError && "input_required" in asked.text && "memory_ask" in asked.text, asked.text)
    val unknown = client.callTool("memory_future", JsonObject(emptyMap()), 2_000)
    assertTrue(unknown.isError && "deferred" in unknown.text, unknown.text)
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
    val tools = DirectChatTools(listOf(MemoryServerSource(connect = { memoryServer.client() }, clientVersion = "test", timeoutMs = 2_000)))
    assertEquals(listOf("memory_search", "memory_save"), tools.specs { throw it }.map { it.name })
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
    assertEquals(McpProtocol.Risk.READ, MemoryServerSource.riskOf("history_search"))
    assertEquals(McpProtocol.Risk.READ, MemoryServerSource.riskOf("project_resolve"))
    assertEquals(McpProtocol.Risk.WRITE, MemoryServerSource.riskOf("memory_delete"))
    assertEquals(McpProtocol.Risk.WRITE, MemoryServerSource.riskOf("memory_export"))
  }

  @Test
  fun `no installed server means no tools, not an error`() {
    assertEquals(emptyList(), DirectChatTools(listOf(MemoryServerSource(connect = { null }, clientVersion = "test"))).specs { throw it })
  }

  @Test
  fun `IDE tools come first, run_agent is not offered, and a failing source does not hide the others`() {
    val calls = ArrayList<String>()
    val ide = IdeToolsSource { name, _ -> calls += name; McpServer.Tools.Result("ok $name") }
    val broken = object : DirectChatTools.Source {
      override fun specs(): List<com.vibe.agent.providers.ToolSpec> = throw McpStdioClient.McpException("down")
      override fun riskOf(tool: String) = McpProtocol.Risk.READ
      override fun call(tool: String, arguments: JsonObject) = McpStdioClient.CallResult("", false)
    }
    val failures = ArrayList<Exception>()
    val tools = DirectChatTools(listOf(ide, broken))
    val names = tools.specs { failures += it }.map { it.name }
    assertTrue(McpProtocol.TOOL_SYMBOL_USAGES in names)
    assertFalse(McpProtocol.TOOL_RUN in names)
    assertEquals(1, failures.size)

    val allow = { _: ToolCall, risk: McpProtocol.Risk -> risk == McpProtocol.Risk.READ }
    assertEquals("ok vibe_symbol_usages", tools.execute(ToolCall("1", McpProtocol.TOOL_SYMBOL_USAGES, "{}"), allow).text)
    assertTrue(tools.execute(ToolCall("2", McpProtocol.TOOL_DECISIONS_RECORD, "{}"), allow).isError, "writing refused")
    assertTrue(tools.execute(ToolCall("3", McpProtocol.TOOL_RUN, "{}"), allow).isError, "not offered")
    assertEquals(listOf(McpProtocol.TOOL_SYMBOL_USAGES), calls)
  }

  @Test
  fun `инструмент, ушедший в долгую работу, не вешает ход`() {
    // Так вставал ход у пользователя 18.09.2026: vibe_project_info строил граф всего монорепозитория,
    // потолок был только у сервера памяти, и человек видел значок вызова и больше ничего.
    val started = java.util.concurrent.CountDownLatch(1)
    val release = java.util.concurrent.CountDownLatch(1)
    val slow = object : DirectChatTools.Source {
      override fun specs() = listOf(com.vibe.agent.providers.ToolSpec("slow_tool", "долгий", JsonObject(emptyMap())))
      override fun riskOf(tool: String) = McpProtocol.Risk.READ
      override fun call(tool: String, arguments: JsonObject): McpStdioClient.CallResult {
        started.countDown()
        release.await(30, java.util.concurrent.TimeUnit.SECONDS)
        return McpStdioClient.CallResult("поздно", false)
      }
    }
    val tools = DirectChatTools(listOf(slow), callTimeoutMs = 300)
    tools.specs { throw it }
    val result = tools.execute(ToolCall("1", "slow_tool", "{}")) { _, _ -> true }
    assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS), "инструмент даже не начали звать")
    assertTrue(result.isError, "ход обязан получить ответ, а не ждать молча")
    assertTrue("slow_tool" in result.text, result.text)
    release.countDown()
  }
}
