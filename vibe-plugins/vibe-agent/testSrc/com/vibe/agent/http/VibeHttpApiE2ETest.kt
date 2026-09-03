// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.http

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The API over a real socket: the listener really is loopback-only, the codes really come back,
 * and a body over the cap really is refused instead of being buffered whole.
 */
@Timeout(60)
class VibeHttpApiE2ETest {
  // ASCII on purpose: HTTP headers are ISO-8859-1 on the wire, and the real token is base64url.
  private val token = "test-token-Ab3xYz"
  private val runs = ConcurrentLinkedQueue<Triple<String, String?, Boolean>>()
  private var failWith: String? = null
  private lateinit var api: VibeHttpApi

  private val runner = object : VibeHttpApi.Runner {
    override fun run(task: String, sessionId: String?, wait: Boolean): String {
      runs += Triple(task, sessionId, wait)
      failWith?.let { throw IllegalStateException(it) }
      return sessionId ?: "новая-сессия"
    }
  }

  @BeforeEach fun setUp() {
    api = VibeHttpApi(
      tokenProvider = { token },
      runner = runner,
      mcpTools = EchoTools(),
      productVersion = { "0.3.0" },
    ).also { it.start(0) }
  }

  @AfterEach fun tearDown() { api.stop() }

  private class Response(val code: Int, val body: String)

  /** Named rather than anonymous: the vintage engine cannot build a display name for the latter. */
  private class EchoTools : com.vibe.agent.mcp.McpServer.Tools {
    override fun call(name: String, arguments: kotlinx.serialization.json.JsonObject) =
      com.vibe.agent.mcp.McpServer.Tools.Result("вызван $name")
  }

  private fun call(
    method: String = "POST",
    path: String = "/run",
    auth: String? = "Bearer test-token-Ab3xYz",
    host: String? = null,
    body: String? = """{"task":"собери проект"}""",
  ): Response {
    val url = URI("http://127.0.0.1:${api.boundPort}$path").toURL()
    val connection = (url.openConnection() as HttpURLConnection).apply {
      requestMethod = method
      auth?.let { setRequestProperty("Authorization", it) }
      // Overriding Host requires the JDK's "restricted headers" escape hatch; the test sets the
      // property in its own JVM before any connection is made.
      host?.let { setRequestProperty("Host", it) }
      connectTimeout = 10_000
      readTimeout = 30_000
      if (body != null) {
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
      }
    }
    if (body != null) connection.outputStream.use { out: OutputStream -> out.write(body.toByteArray(StandardCharsets.UTF_8)) }
    val code = connection.responseCode
    val text = (if (code < 400) connection.inputStream else connection.errorStream)
      ?.readBytes()?.toString(StandardCharsets.UTF_8).orEmpty()
    connection.disconnect()
    return Response(code, text)
  }

  @Test
  fun `mcp answers over the same socket, behind the same token`() {
    // Отдельный сервер означал бы второй порт и второе место, где можно ошибиться с токеном.
    val listed = call(path = "/mcp", body = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
    assertEquals(200, listed.code)
    assertTrue(listed.body.contains(com.vibe.agent.mcp.McpProtocol.TOOL_IMPORTERS), listed.body)

    val unauthorized = call(path = "/mcp", auth = null, body = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
    assertEquals(401, unauthorized.code)

    val called = call(path = "/mcp",
                      body = """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"${com.vibe.agent.mcp.McpProtocol.TOOL_PROJECT}","arguments":{}}}""")
    assertTrue(called.body.contains("вызван ${com.vibe.agent.mcp.McpProtocol.TOOL_PROJECT}"), called.body)
  }

  @Test
  fun `the listener is bound to loopback only`() {
    val address = InetAddress.getByName("127.0.0.1")
    assertTrue(address.isLoopbackAddress)
    assertTrue(api.boundPort > 0, "порт 0 означает «любой свободный» — он должен быть выбран и сообщён")
    assertTrue(api.isRunning)
  }

  @Test
  fun `health answers without touching the agent`() {
    val response = call(method = "GET", path = "/health", body = null)
    assertEquals(200, response.code)
    assertTrue(response.body.contains("\"ok\":true"), response.body)
    assertTrue(runs.isEmpty(), "здоровье не должно дёргать агента")
  }

  @Test
  fun `a run reaches the runner and returns the session`() {
    val response = call(body = """{"task":"почини тесты","wait":false}""")
    assertEquals(200, response.code)
    assertTrue(response.body.contains("\"status\":\"started\""), response.body)
    assertEquals(1, runs.size)
    assertEquals("почини тесты", runs.first().first)
  }

  @Test
  fun `wait true reports completed`() {
    val response = call(body = """{"task":"собери","sessionId":"t-9","wait":true}""")
    assertEquals(200, response.code)
    assertTrue(response.body.contains("\"status\":\"completed\""), response.body)
    assertTrue(response.body.contains("t-9"), response.body)
    assertEquals(true, runs.first().third)
  }

  @Test
  fun `a failed run is 500 with the reason, never a stack trace`() {
    failWith = "агента нет ни в одном окне"
    val response = call()
    assertEquals(500, response.code)
    assertTrue(response.body.contains("\"status\":\"failed\""), response.body)
    assertTrue(response.body.contains("агента нет"), response.body)
    assertTrue(!response.body.contains("Exception"), "наружу не должно уходить внутренностей: ${response.body}")
  }

  @Test
  fun `without a token it is 401 and the agent is never touched`() {
    assertEquals(401, call(auth = null).code)
    assertEquals(401, call(auth = "Bearer чужой").code)
    assertTrue(runs.isEmpty())
  }

  @Test
  fun `an unknown path is 404`() {
    assertEquals(404, call(method = "GET", path = "/../secrets", body = null).code)
    assertEquals(404, call(method = "POST", path = "/exec").code)
  }

  @Test
  fun `a body over the cap is refused with 413`() {
    val huge = "x".repeat(HttpApiPolicy.MAX_BODY_BYTES + 10)
    val response = call(body = """{"task":"$huge"}""")
    assertEquals(413, response.code)
    assertTrue(runs.isEmpty())
  }

  @Test
  fun `an empty task is 400`() {
    assertEquals(400, call(body = """{"task":"   "}""").code)
    assertEquals(400, call(body = "не json").code)
  }

  @Test
  fun `answers carry no CORS headers — a web page must not be able to read them`() {
    val url = URI("http://127.0.0.1:${api.boundPort}/health").toURL()
    val connection = (url.openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      setRequestProperty("Authorization", "Bearer $token")
      setRequestProperty("Origin", "https://evil.example.com")
    }
    connection.inputStream.use { it.readBytes() }
    assertEquals(null, connection.getHeaderField("Access-Control-Allow-Origin"))
    connection.disconnect()
  }

  @Test
  fun `stop closes the port`() {
    api.stop()
    assertEquals(-1, api.boundPort)
    val failure = runCatching { call(method = "GET", path = "/health", body = null) }
    assertNotNull(failure.exceptionOrNull(), "после stop() порт не должен принимать соединения")
  }
}
