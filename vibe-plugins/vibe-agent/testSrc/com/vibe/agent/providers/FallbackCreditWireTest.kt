// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The credit on the wire: the token goes in the body, and a token the vendor refuses is dropped, not the answer */
class FallbackCreditWireTest {
  private val bodies = CopyOnWriteArrayList<JsonObject>()

  /** Answers that the stub gives in turn: a status and a body */
  private val script = ArrayDeque<Pair<Int, String>>()

  private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
    createContext("/") { exchange ->
      bodies += Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
      val (status, text) = synchronized(script) { script.removeFirstOrNull() } ?: (200 to "data: [DONE]\n\n")
      val bytes = text.toByteArray()
      exchange.responseHeaders.add("Content-Type", if (status == 200) "text/event-stream" else "application/json")
      exchange.sendResponseHeaders(status, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    start()
  }

  private val baseUrl = "http://127.0.0.1:${server.address.port}/v1"

  @AfterTest
  fun stop() = server.stop(0)

  private fun send(token: String?) {
    val settings = object : LlmSettings {
      override val offline: Boolean = false
      override val reasoningLevel: String = "off"
    }
    val provider = ResolvedProvider(ProviderEntry(id = "stub", baseURL = baseUrl, protocol = "anthropic"),
                                    "anthropic", baseUrl, apiKey = "k", localAddress = true)
    val http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()
    LlmClient({ http }, null, settings).chat(provider, ModelEntry(id = "claude-opus-4-8"),
                                              listOf(ChatMessage("user", "hello")), fallbackCredit = token) { }
  }

  private fun token(body: JsonObject) = body[FallbackCredit.FIELD]?.jsonPrimitive?.content

  @Test
  fun `the token travels in the body`() {
    send("tok")
    assertEquals(listOf("tok"), bodies.map { token(it) })
  }

  @Test
  fun `a refused token is dropped and the request goes again without it`() {
    script += 400 to """{"type":"error","error":{"type":"invalid_request_error","message":"invalid fallback_credit_token"}}"""
    send("tok")
    assertEquals(listOf("tok", null), bodies.map { token(it) })
  }

  @Test
  fun `a transient refusal repeats once with the token, then drops it`() {
    repeat(2) { script += 400 to """{"error":{"message":"redemption temporarily unavailable"}}""" }
    send("tok")
    assertEquals(listOf("tok", "tok", null), bodies.map { token(it) })
  }

  @Test
  fun `without a token nothing is added`() {
    send(null)
    assertNull(token(bodies.single()))
  }
}
