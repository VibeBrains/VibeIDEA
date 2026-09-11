// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.util.Base64
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

  private fun ask(body: String, tools: McpServer.Tools = FakeTools(), headers: McpServer.Headers = McpServer.Headers()) =
    McpServer.handle(body, serverVersion = "0.3.0", tools = tools, headers = headers)

  /** A request of the 2026 revision as a conforming client sends it: the version sits in `params._meta`. */
  private fun modern(method: String, params: String = ""): String {
    val meta = """"_meta":{"${McpProtocol.Meta.PROTOCOL_VERSION}":"${McpProtocol.VERSION_2026}"}"""
    val inner = if (params.isEmpty()) meta else "$params,$meta"
    return """{"jsonrpc":"2.0","id":1,"method":"$method","params":{$inner}}"""
  }

  /** …and the headers that mirror it. */
  private fun mirrored(method: String, name: String? = null) =
    McpServer.Headers(protocolVersion = McpProtocol.VERSION_2026, method = method, name = name)

  private fun base64(value: String) =
    "=?base64?" + Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8)) + "?="

  private fun result(body: String?) = json.parseToJsonElement(body!!).jsonObject["result"]!!.jsonObject
  private fun error(body: String?) = json.parseToJsonElement(body!!).jsonObject["error"]!!.jsonObject
  private fun code(answer: McpServer.Answer) = error(answer.body)["code"]!!.jsonPrimitive.int

  @Test
  fun `server discover lists supportedVersions and names the server in _meta`() {
    // The field name is the spec's (2026-07-28, server/discover): under any other name a client of
    // the new revision finds no versions at all.
    val answer = ask(modern("server/discover"), headers = mirrored("server/discover"))
    assertEquals(200, answer.httpStatus)
    val res = result(answer.body)
    assertEquals(listOf(McpProtocol.VERSION_2026, McpProtocol.VERSION_2025),
                 res["supportedVersions"]!!.jsonArray.map { it.jsonPrimitive.content })
    assertEquals("vibeidea", res["_meta"]!!.jsonObject[McpProtocol.Meta.SERVER_INFO]!!.jsonObject["name"]!!.jsonPrimitive.content)
  }

  @Test
  fun `the handshake of the older revision answers with the version the client asked for`() {
    // Ответ «у нас только новая» сделал бы нас правильными и непригодными: большинство клиентов
    // до сих пор открывают разговор initialize.
    val answer = ask("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")
    assertEquals("2025-06-18", result(answer.body)["protocolVersion"]!!.jsonPrimitive.content)
  }

  @Test
  fun `a handshake asking for a version we do not have is answered with ours, not refused`() {
    // Lifecycle 2025-06-18: «Otherwise, the server MUST respond with another protocol version it
    // supports». A 2025-11-25 client offered 2025-06-18 carries on; refused, it had nowhere to go.
    for (asked in listOf("2025-11-25", "1999-01-01")) {
      val answer = ask("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$asked"}}""")
      assertEquals(200, answer.httpStatus, asked)
      assertEquals(McpProtocol.VERSION_2025, result(answer.body)["protocolVersion"]!!.jsonPrimitive.content, asked)
    }
  }

  @Test
  fun `an unsupported version is refused with 400 and the list to retry with`() {
    // The schema makes `data` mandatory on -32022: the client retries with one of `supported`.
    val body = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"${McpProtocol.Meta.PROTOCOL_VERSION}":"1999-01-01"}}}"""
    for (headers in listOf(McpServer.Headers(), McpServer.Headers(protocolVersion = "1999-01-01", method = "tools/list"))) {
      val answer = ask(body, headers = headers)
      assertEquals(400, answer.httpStatus)
      assertEquals(McpProtocol.Error.UNSUPPORTED_PROTOCOL_VERSION, code(answer))
      val data = error(answer.body)["data"]!!.jsonObject
      assertEquals(McpProtocol.SUPPORTED, data["supported"]!!.jsonArray.map { it.jsonPrimitive.content })
      assertEquals("1999-01-01", data["requested"]!!.jsonPrimitive.content)
    }
  }

  @Test
  fun `the version is also read from a top-level _meta`() {
    val body = """{"jsonrpc":"2.0","id":1,"method":"tools/list","_meta":{"io.modelcontextprotocol/protocolVersion":"1999-01-01"}}"""
    assertEquals(McpProtocol.Error.UNSUPPORTED_PROTOCOL_VERSION, code(ask(body)))
  }

  @Test
  fun `a request of the new revision without MCP-Protocol-Version is refused`() {
    val answer = ask(modern("tools/list"), headers = McpServer.Headers(method = "tools/list"))
    assertEquals(400, answer.httpStatus)
    assertEquals(McpProtocol.Error.HEADER_MISMATCH, code(answer))
  }

  @Test
  fun `the version header and _meta must agree`() {
    val older = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"${McpProtocol.Meta.PROTOCOL_VERSION}":"${McpProtocol.VERSION_2025}"}}}"""
    val disagreeing = ask(older, headers = mirrored("tools/list"))
    assertEquals(400, disagreeing.httpStatus)
    assertEquals(McpProtocol.Error.HEADER_MISMATCH, code(disagreeing))
    // The header names 2026-07-28 and the body names nothing — which the schema forbids: the version
    // in `_meta` is required on every request of that revision.
    val silent = ask("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", headers = mirrored("tools/list"))
    assertEquals(400, silent.httpStatus)
    assertEquals(McpProtocol.Error.HEADER_MISMATCH, code(silent))
  }

  @Test
  fun `the new revision requires Mcp-Method and Mcp-Name, and nothing runs without them`() {
    val tools = FakeTools()
    val call = modern("tools/call", """"name":"${McpProtocol.TOOL_PROJECT}","arguments":{}""")
    val noMethod = ask(call, tools, McpServer.Headers(protocolVersion = McpProtocol.VERSION_2026, name = McpProtocol.TOOL_PROJECT))
    assertEquals(400, noMethod.httpStatus)
    assertEquals(McpProtocol.Error.HEADER_MISMATCH, code(noMethod))
    val noName = ask(call, tools, mirrored("tools/call"))
    assertEquals(400, noName.httpStatus)
    assertEquals(McpProtocol.Error.HEADER_MISMATCH, code(noName))
    assertNull(tools.lastName, "запрос, не прошедший сверку, не должен доходить до исполнителя")

    val complete = ask(call, tools, mirrored("tools/call", McpProtocol.TOOL_PROJECT))
    assertEquals(200, complete.httpStatus)
    assertEquals(McpProtocol.TOOL_PROJECT, tools.lastName)
  }

  @Test
  fun `a base64 Mcp-Name is decoded before the comparison`() {
    val tools = FakeTools()
    val call = modern("tools/call", """"name":"${McpProtocol.TOOL_PROJECT}","arguments":{}""")
    assertEquals(200, ask(call, tools, mirrored("tools/call", base64(McpProtocol.TOOL_PROJECT))).httpStatus)

    // A non-ASCII name travels only encoded. Decoded, it matches the body and meets the ordinary
    // «unknown tool» refusal — not a header mismatch.
    val cyrillic = modern("tools/call", """"name":"инструмент","arguments":{}""")
    assertEquals(McpProtocol.Error.INVALID_PARAMS, code(ask(cyrillic, tools, mirrored("tools/call", base64("инструмент")))))

    // Broken base64 and a raw non-ASCII value are malformed headers, which the spec counts as a
    // failed validation.
    for (bad in listOf("=?base64?***?=", "инструмент")) {
      val answer = ask(cyrillic, tools, mirrored("tools/call", bad))
      assertEquals(400, answer.httpStatus, bad)
      assertEquals(McpProtocol.Error.HEADER_MISMATCH, code(answer), bad)
    }
  }

  @Test
  fun `an unknown method is 404 in the new revision and a plain JSON-RPC error in the old`() {
    // 404 is what 2026-07-28 prescribes. A client of the older revision reads the error from the
    // body of a 200, and to it a 404 says that the session or the endpoint is gone.
    val newer = ask(modern("resources/list"), headers = mirrored("resources/list"))
    assertEquals(404, newer.httpStatus)
    assertEquals(McpProtocol.Error.METHOD_NOT_FOUND, code(newer))
    val older = ask("""{"jsonrpc":"2.0","id":1,"method":"resources/list"}""")
    assertEquals(200, older.httpStatus)
    assertEquals(McpProtocol.Error.METHOD_NOT_FOUND, code(older))
  }

  @Test
  fun `ping gets an empty result`() {
    // 2025-06-18: «The receiver MUST respond promptly with an empty response» — empty literally
    // there; the 2026 revision requires resultType on every result, this one included.
    val older = ask("""{"jsonrpc":"2.0","id":"p-1","method":"ping"}""")
    assertEquals(200, older.httpStatus)
    assertEquals(JsonObject(emptyMap()), result(older.body))
    val newer = result(ask(modern("ping"), headers = mirrored("ping")).body)
    assertEquals("complete", newer["resultType"]!!.jsonPrimitive.content)
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
    assertEquals(McpProtocol.Error.INVALID_PARAMS, code(ask(body, tools)))
    assertNull(tools.lastName, "неизвестное имя не должно доходить до исполнителя")
  }

  @Test
  fun `a notification gets no body at all`() {
    // Ответ на то, о чём не спрашивали, — протокольная ошибка на нашей стороне.
    val answer = ask("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
    assertNull(answer.body)
    assertEquals(202, answer.httpStatus)
    // The 2026 revision defines no header requirements for a notification.
    val newer = ask("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"_meta":{"${McpProtocol.Meta.PROTOCOL_VERSION}":"${McpProtocol.VERSION_2026}"}}}""",
                    headers = McpServer.Headers(protocolVersion = McpProtocol.VERSION_2026))
    assertNull(newer.body)
    assertEquals(202, newer.httpStatus)
  }

  @Test
  fun `a header that contradicts the body is refused with 400`() {
    val method = ask("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", headers = McpServer.Headers(method = "tools/call"))
    assertEquals(400, method.httpStatus)
    assertEquals(McpProtocol.Error.HEADER_MISMATCH, code(method))
    // Checked in the older revision too: whoever routed by the header may have trusted it.
    val tools = FakeTools()
    val name = ask("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"${McpProtocol.TOOL_RUN}","arguments":{"task":"x"}}}""",
                   tools, McpServer.Headers(method = "tools/call", name = McpProtocol.TOOL_PROJECT))
    assertEquals(400, name.httpStatus)
    assertEquals(McpProtocol.Error.HEADER_MISMATCH, code(name))
    assertNull(tools.lastName, "маршрутизировали одно — исполнилось бы другое")
  }

  @Test
  fun `a missing header is accepted, because that is what the older clients send`() {
    val answer = ask("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
    assertTrue(result(answer.body).containsKey("tools"))
  }

  @Test
  fun `garbage and a missing method are named, not swallowed`() {
    val garbage = ask("не json")
    assertEquals(400, garbage.httpStatus)
    assertEquals(McpProtocol.Error.PARSE, code(garbage))
    assertEquals(McpProtocol.Error.INVALID_REQUEST, code(ask("""{"jsonrpc":"2.0","id":1}""")))
    // A method that is not a string is a malformed request, not a crash of the listener.
    assertEquals(McpProtocol.Error.INVALID_REQUEST, code(ask("""{"jsonrpc":"2.0","id":1,"method":{"x":1}}""")))
    assertEquals(McpProtocol.Error.METHOD_NOT_FOUND, code(ask("""{"jsonrpc":"2.0","id":1,"method":"resources/list"}""")))
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
