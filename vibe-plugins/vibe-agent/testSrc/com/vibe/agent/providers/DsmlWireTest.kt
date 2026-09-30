// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.sun.net.httpserver.HttpServer
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

/** A call DeepSeek wrote as text on the wire: read as a call, kept off the feed, and only for the models that do it */
class DsmlWireTest {
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

  private fun send(model: String): Pair<LlmClient, String> {
    val settings = object : LlmSettings {
      override val offline: Boolean = false
      override val reasoningLevel: String = "off"
    }
    val provider = ResolvedProvider(ProviderEntry(id = "deepseek", baseURL = baseUrl, protocol = "openai"),
                                    "openai", baseUrl, apiKey = "k", localAddress = true)
    val http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()
    val client = LlmClient({ http }, null, settings)
    val shown = StringBuilder()
    client.chat(provider, ModelEntry(id = model), listOf(ChatMessage("user", "branch?"))) { shown.append(it) }
    return client to shown.toString()
  }

  private val call = "Смотрю ветку.\n\n<｜DSML｜function_calls>\n<｜DSML｜invoke name=\"git_state\">\n" +
                     "<｜DSML｜parameter name=\"what\" string=\"true\">branch</｜DSML｜parameter>\n</｜DSML｜invoke>\n</｜DSML｜function_calls>"

  @Test
  fun `deepseek's call in the text becomes a call, and only the words reach the feed`() {
    content = call
    val (client, shown) = send("deepseek-flash")
    assertEquals("Смотрю ветку.\n\n", shown)
    val calls = client.lastToolCalls()
    assertEquals(listOf("git_state"), calls.map { it.name })
    assertEquals("""{"what":"branch"}""", calls.single().arguments)
    assertNull(client.lastUnparsedToolMarkup())
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
  fun `another model's text is its answer, markup or not`() {
    content = call
    val (client, shown) = send("gpt-6-luna")
    assertEquals(call, shown)
    assertTrue(client.lastToolCalls().isEmpty())
    assertNull(client.lastUnparsedToolMarkup())
  }
}
