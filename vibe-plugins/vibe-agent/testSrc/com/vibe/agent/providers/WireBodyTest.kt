// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What a request carries, read where the provider reads it: a stub server records the request [LlmClient] sends
 *
 * A test of how the prompt is assembled cannot see a message lost on the way out
 * An SDK adapter that dropped `system` and `developer` messages left a model without its system prompt
 * With every assembly test green, and only a stub writing request bodies showed it
 * Here the same stub holds all four wires: the system prompt in the field of its wire, never as a role in the list
 */
class WireBodyTest {
  private data class Seen(val path: String, val headers: Map<String, String?>, val body: JsonObject)

  private val seen = CopyOnWriteArrayList<Seen>()

  private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
    createContext("/") { exchange -> answer(exchange) }
    start()
  }

  private val baseUrl = "http://127.0.0.1:${server.address.port}/v1"

  private val http: HttpClient = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()

  private val messages = listOf(
    ChatMessage("system", "RULE-ONE"),
    ChatMessage("user", "hello"),
    // A second system message mid-conversation, as the summary of a compacted history is
    ChatMessage("system", "RULE-TWO"),
  )

  @AfterTest
  fun stop() {
    server.stop(0)
    ModelQuirksRegistry.forget(QUIRK_PROJECT)
  }

  /** An empty stream: every wire ends its answer on it without an error, and the request is what is under test */
  private fun answer(exchange: HttpExchange) {
    seen += Seen(exchange.requestURI.toString(), HEADERS.associateWith { exchange.requestHeaders.getFirst(it) },
                 Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject)
    val text = "data: [DONE]\n\n".toByteArray()
    exchange.responseHeaders.add("Content-Type", "text/event-stream")
    exchange.sendResponseHeaders(200, text.size.toLong())
    exchange.responseBody.use { it.write(text) }
  }

  private fun send(
    protocol: String,
    modelId: String = "m",
    offline: Boolean = false,
    runsLocally: Boolean? = null,
    projectBase: String? = null,
  ): Seen? {
    val settings = object : LlmSettings {
      override val offline: Boolean = offline
      override val reasoningLevel: String = "off"
    }
    val provider = ResolvedProvider(
      ProviderEntry(id = "stub", baseURL = baseUrl, protocol = protocol, runsLocally = runsLocally),
      protocol, baseUrl, apiKey = KEY, localAddress = LocalAddress.isLocal(baseUrl),
    )
    LlmClient({ http }, projectBase, settings).chat(provider, ModelEntry(id = modelId), messages) { }
    return seen.lastOrNull()
  }

  @Test
  fun `anthropic takes the system prompt in its own field`() {
    val request = send("anthropic")!!
    assertEquals("/v1/messages", request.path)
    assertEquals("RULE-ONE\nRULE-TWO", request.body["system"]!!.jsonPrimitive.content)
    assertEquals(listOf("user"), roles(request.body, "messages"))
    assertEquals(KEY, request.headers["x-api-key"])
  }

  @Test
  fun `openai carries system messages in the list, where they were`() {
    val request = send("openai")!!
    assertEquals("/v1/chat/completions", request.path)
    val list = request.body["messages"]!!.jsonArray.map { it.jsonObject }
    assertEquals(listOf("system", "user", "system"), list.map { it["role"]!!.jsonPrimitive.content })
    assertEquals(listOf("RULE-ONE", "hello", "RULE-TWO"), list.map { it["content"]!!.jsonPrimitive.content })
    assertEquals("Bearer $KEY", request.headers["Authorization"])
  }

  @Test
  fun `the responses wire takes the system prompt as instructions`() {
    val request = send(ModelQuirks.WIRE_OPENAI_RESPONSES)!!
    assertEquals("/v1/responses", request.path)
    assertEquals("RULE-ONE\nRULE-TWO", request.body["instructions"]!!.jsonPrimitive.content)
    assertEquals(listOf("user"), roles(request.body, "input"))
    assertEquals("Bearer $KEY", request.headers["Authorization"])
  }

  @Test
  fun `gemini takes the system prompt as its instruction`() {
    val request = send("gemini")!!
    assertEquals("/v1/models/m:streamGenerateContent?alt=sse", request.path)
    val parts = request.body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray
    assertEquals("RULE-ONE\nRULE-TWO", parts.single().jsonObject["text"]!!.jsonPrimitive.content)
    assertEquals(listOf("user"), roles(request.body, "contents"))
    assertEquals(KEY, request.headers["x-goog-api-key"])
  }

  @Test
  fun `a model without a system role gets the prompt as the first user message`() {
    ModelQuirksRegistry.install(QUIRK_PROJECT, listOf(
      ModelQuirks.Rule(Regex("^no-system-role$"), setOf(ModelQuirks.Quirk.NO_SYSTEM_ROLE), "test")))
    val request = send("openai", modelId = "no-system-role", projectBase = QUIRK_PROJECT)!!
    val list = request.body["messages"]!!.jsonArray.map { it.jsonObject }
    assertEquals(listOf("user", "user"), list.map { it["role"]!!.jsonPrimitive.content })
    assertEquals("RULE-ONE\n\nRULE-TWO", list.first()["content"]!!.jsonPrimitive.content)
  }

  @Test
  fun `offline mode stops a model that does not run here, whatever its address`() {
    // A proxy on localhost to a cloud model: local by address, and yet the request would leave the machine
    assertFailsWith<IllegalStateException> { send("openai", offline = true, runsLocally = false) }
    assertTrue(seen.isEmpty(), "запрос ушёл при офлайн-режиме")
    // Undeclared, a server at this machine's address is a local model and is asked
    assertEquals("/v1/chat/completions", send("openai", offline = true)!!.path)
  }

  /** The roles of a list in the body; an item without a role (a Responses item of another type) is not a message */
  private fun roles(body: JsonObject, list: String): List<String> =
    body[list]!!.jsonArray.mapNotNull { it.jsonObject["role"]?.jsonPrimitive?.content }

  private companion object {
    const val KEY = "test-key"
    const val QUIRK_PROJECT = "/wire-body-test"
    val HEADERS = listOf("Authorization", "x-api-key", "x-goog-api-key")
  }
}
