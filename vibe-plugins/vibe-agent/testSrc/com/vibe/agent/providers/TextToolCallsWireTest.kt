// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.http.HttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A call written as text on the wire: read as a call and kept off the feed for any model the request offered tools to,
 * and left as the answer when it offered none or the markup calls a tool it did not offer
 */
class TextToolCallsWireTest {
  @Volatile private var content = ""

  private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
    createContext("/") { exchange ->
      exchange.requestBody.readAllBytes()
      // The answer in small chunks: tags arrive cut, as they do from the vendor
      val chunks = content.chunked(5).joinToString("") { piece ->
        val delta = buildJsonObject { put("content", JsonPrimitive(piece)) }
        "data: {\"choices\":[{\"index\":0,\"delta\":$delta}]}\n\n"
      } + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
      val bytes = chunks.toByteArray()
      exchange.responseHeaders.add("Content-Type", "text/event-stream")
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    start()
  }

  private val baseUrl = "http://127.0.0.1:${server.address.port}/v1"

  @AfterTest
  fun stop() = server.stop(0)

  private val gitState = ToolSpec("git_state", "The branch and the changes", JsonObject(emptyMap()))

  private fun send(model: String, tools: List<ToolSpec> = listOf(gitState)): Pair<LlmClient, String> {
    val settings = object : LlmSettings {
      override val offline: Boolean = false
      override val reasoningLevel: String = "off"
    }
    val provider = ResolvedProvider(ProviderEntry(id = "local", baseURL = baseUrl, protocol = "openai"),
                                    "openai", baseUrl, apiKey = "k", localAddress = true)
    val http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()
    val client = LlmClient({ http }, null, settings)
    val shown = StringBuilder()
    client.chat(provider, ModelEntry(id = model), listOf(ChatMessage("user", "branch?")), tools = tools) { shown.append(it) }
    return client to shown.toString()
  }

  private val dsml = "Смотрю ветку.\n\n<｜DSML｜function_calls>\n<｜DSML｜invoke name=\"git_state\">\n" +
                     "<｜DSML｜parameter name=\"what\" string=\"true\">branch</｜DSML｜parameter>\n" +
                     "</｜DSML｜invoke>\n</｜DSML｜function_calls>"

  private val hermes =
    "Смотрю ветку.\n\n<tool_call>\n{\"name\": \"git_state\", \"arguments\": {\"what\": \"branch\"}}\n</tool_call>"

  @Test
  fun `deepseek's call in the text becomes a call, and only the words reach the feed`() {
    content = dsml
    val (client, shown) = send("deepseek-flash")
    assertEquals("Смотрю ветку.\n\n", shown)
    val calls = client.lastToolCalls()
    assertEquals(listOf("git_state"), calls.map { it.name })
    assertEquals("""{"what":"branch"}""", calls.single().arguments)
    assertNull(client.lastUnparsedToolMarkup())
  }

  @Test
  fun `the same markup from a model of another name is read the same`() {
    content = dsml
    val (client, shown) = send("qwen3.6-plus")
    assertEquals("Смотрю ветку.\n\n", shown)
    assertEquals(listOf("git_state"), client.lastToolCalls().map { it.name })
  }

  @Test
  fun `another family's markup is read by its own format`() {
    content = hermes
    val (client, shown) = send("my-local-finetune")
    assertEquals("Смотрю ветку.\n\n", shown)
    assertEquals("""{"what":"branch"}""", client.lastToolCalls().single().arguments)
  }

  @Test
  fun `markup that does not parse is held back and named, not shown`() {
    content = "<｜DSML｜function_calls>\n<｜DSML｜invoke name=\"git_state\">\n<｜DSML｜parameter name=\"what\" string=\"tr"
    val (client, shown) = send("deepseek-flash")
    assertEquals("", shown)
    assertTrue(client.lastToolCalls().isEmpty())
    assertEquals(content, client.lastUnparsedToolMarkup())
  }

  @Test
  fun `without tools on offer the markup is the answer`() {
    content = dsml
    val (client, shown) = send("deepseek-flash", tools = emptyList())
    assertEquals(dsml, shown)
    assertTrue(client.lastToolCalls().isEmpty())
    assertNull(client.lastUnparsedToolMarkup())
  }

  @Test
  fun `a call to a tool that was not offered is the answer`() {
    content = hermes.replace("git_state", "rm_rf")
    val (client, shown) = send("my-local-finetune")
    assertEquals(content, shown)
    assertTrue(client.lastToolCalls().isEmpty())
    assertNull(client.lastUnparsedToolMarkup())
  }

  @Test
  fun `markup in a code block is the answer`() {
    content = "Так выглядит вызов:\n\n```\n$hermes\n```\n"
    val (client, shown) = send("my-local-finetune")
    assertEquals(content, shown)
    assertTrue(client.lastToolCalls().isEmpty())
  }
}
