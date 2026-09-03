// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpServerTest {
  private val json = Json { ignoreUnknownKeys = true }

  private class FakeTools(val answer: McpServer.Tools.Result = McpServer.Tools.Result("ok")) : McpServer.Tools {
    var lastName: String? = null
    var lastArguments: JsonObject? = null
    var throwOn: String? = null

    override fun call(name: String, arguments: JsonObject): McpServer.Tools.Result {
      lastName = name
      lastArguments = arguments
      if (name == throwOn) throw IllegalStateException("инструмент упал")
      return answer
    }
  }

  private fun ask(body: String, tools: McpServer.Tools = FakeTools(), header: String? = null) =
    McpServer.handle(body, serverVersion = "0.3.0", tools = tools, mcpMethodHeader = header)

  private fun result(body: String?) = json.parseToJsonElement(body!!).jsonObject["result"]!!.jsonObject
  private fun error(body: String?) = json.parseToJsonElement(body!!).jsonObject["error"]!!.jsonObject

  @Test
  fun `server discover advertises both revisions and identifies the server`() {
    // Клиент 2026 года начинает разговор именно с него: обязательный метод, а не удобство.
    val answer = ask("""{"jsonrpc":"2.0","id":1,"method":"server/discover"}""")
    val versions = result(answer.body)["protocolVersions"]!!.jsonArray.map { it.jsonPrimitive.content }
    assertEquals(listOf(McpProtocol.VERSION_2026, McpProtocol.VERSION_2025), versions)
    assertEquals("vibeidea", result(answer.body)["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
  }

  @Test
  fun `the handshake of the older revision answers with the version the client asked for`() {
    // Ответ «у нас только новая» сделал бы нас правильными и непригодными: большинство клиентов
    // до сих пор открывают разговор initialize.
    val answer = ask("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")
    assertEquals("2025-06-18", result(answer.body)["protocolVersion"]!!.jsonPrimitive.content)
  }

  @Test
  fun `an unsupported protocol version is refused by its own error code`() {
    val answer = ask("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}""")
    assertEquals(McpProtocol.Error.UNSUPPORTED_PROTOCOL_VERSION, error(answer.body)["code"]!!.jsonPrimitive.int)
  }

  @Test
  fun `the version is also read from _meta, where the stateless revision carries it`() {
    val body = """{"jsonrpc":"2.0","id":1,"method":"tools/list","_meta":{"io.modelcontextprotocol/protocolVersion":"1999-01-01"}}"""
    assertEquals(McpProtocol.Error.UNSUPPORTED_PROTOCOL_VERSION, error(ask(body).body)["code"]!!.jsonPrimitive.int)
  }

  @Test
  fun `a listing is deterministic and carries cache hints`() {
    // Порядок закреплён спекой 2026-07-28: перетасовка каталога рвёт кэш клиента и кэш промпта,
    // не меняя при этом ничего по существу.
    val listed = result(ask("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").body)
    val names = listed["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
    assertEquals(McpProtocol.TOOLS.map { it.name }, names)
    assertEquals(McpProtocol.LIST_TTL_MS, listed["ttlMs"]!!.jsonPrimitive.content.toLong())
    assertEquals("private", listed["cacheScope"]!!.jsonPrimitive.content)
  }

  @Test
  fun `every tool declares a schema and a description`() {
    // Инструмент без описания модель зовёт наугад, а инструмент без схемы — с чем попало.
    assertTrue(McpProtocol.TOOLS.all { it.description.isNotBlank() && it.title.isNotBlank() })
    assertTrue(McpProtocol.TOOLS.all { it.schema["type"]?.jsonPrimitive?.contentOrNull == "object" })
    assertEquals(McpProtocol.TOOLS.size, McpProtocol.TOOLS.map { it.name }.toSet().size, "имена уникальны")
  }

  @Test
  fun `a call reaches the tool with its arguments`() {
    val tools = FakeTools(McpServer.Tools.Result("src/main.ts  [факт]  App"))
    val body = """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"${McpProtocol.TOOL_IMPORTERS}","arguments":{"path":"src/App.ts"}}}"""
    val res = result(ask(body, tools).body)
    assertEquals(McpProtocol.TOOL_IMPORTERS, tools.lastName)
    assertEquals("src/App.ts", tools.lastArguments!!["path"]!!.jsonPrimitive.content)
    assertEquals("src/main.ts  [факт]  App", res["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content)
    assertEquals(false, res["isError"]!!.jsonPrimitive.content.toBoolean())
  }

  @Test
  fun `a failing tool is a result with isError, not a protocol error`() {
    // Запрос был корректным, упало исполнение: модель должна увидеть, ЧТО пошло не так, и решить,
    // что делать дальше. Ошибка JSON-RPC отняла бы у неё этот текст.
    val tools = FakeTools().apply { throwOn = McpProtocol.TOOL_RUN }
    val body = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"${McpProtocol.TOOL_RUN}","arguments":{"task":"x"}}}"""
    val res = result(ask(body, tools).body)
    assertEquals(true, res["isError"]!!.jsonPrimitive.content.toBoolean())
    assertTrue(res["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content.contains("упал"))
  }

  @Test
  fun `an unknown tool is refused before anything is executed`() {
    val tools = FakeTools()
    val body = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"rm_rf","arguments":{}}}"""
    assertEquals(McpProtocol.Error.INVALID_PARAMS, error(ask(body, tools).body)["code"]!!.jsonPrimitive.int)
    assertNull(tools.lastName, "неизвестное имя не должно доходить до исполнителя")
  }

  @Test
  fun `a notification gets no body at all`() {
    // Ответ на то, о чём не спрашивали, — протокольная ошибка на нашей стороне.
    val answer = ask("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
    assertNull(answer.body)
    assertEquals(202, answer.httpStatus)
  }

  @Test
  fun `a header that contradicts the body is refused`() {
    val answer = ask("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", header = "tools/call")
    assertEquals(McpProtocol.Error.HEADER_MISMATCH, error(answer.body)["code"]!!.jsonPrimitive.int)
  }

  @Test
  fun `a missing header is accepted, because that is what the older clients send`() {
    val answer = ask("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", header = null)
    assertTrue(result(answer.body).containsKey("tools"))
  }

  @Test
  fun `garbage and a missing method are named, not swallowed`() {
    assertEquals(McpProtocol.Error.PARSE, error(ask("не json").body)["code"]!!.jsonPrimitive.int)
    assertEquals(McpProtocol.Error.INVALID_REQUEST, error(ask("""{"jsonrpc":"2.0","id":1}""").body)["code"]!!.jsonPrimitive.int)
    assertEquals(McpProtocol.Error.METHOD_NOT_FOUND,
                 error(ask("""{"jsonrpc":"2.0","id":1,"method":"resources/list"}""").body)["code"]!!.jsonPrimitive.int)
  }

  @Test
  fun `every result carries resultType and the server identifies itself`() {
    // Требование ревизии 2026-07-28; клиент прежней ревизии незнакомое поле игнорирует.
    val res = result(ask("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").body)
    assertEquals("complete", res["resultType"]!!.jsonPrimitive.content)
    val info = res["_meta"]!!.jsonObject[McpProtocol.Meta.SERVER_INFO]!!.jsonObject
    assertEquals("0.3.0", info["version"]!!.jsonPrimitive.content)
  }
}
