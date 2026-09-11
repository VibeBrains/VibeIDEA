// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.http.VibeHttpApi
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The doctor's probe against the real listener: the wiring the pure suites cannot see. */
@Timeout(60)
class McpSelfProbeTest {
  // ASCII on purpose: the token travels in a header.
  private val token = "probe-token-Qw7"
  private lateinit var api: VibeHttpApi

  /** Named rather than anonymous: the vintage engine cannot build a display name for the latter. */
  private class NoRuns : VibeHttpApi.Runner {
    override fun run(task: String, sessionId: String?, wait: Boolean): String = error("проба не должна ставить задач")
  }

  private class NoTools : McpServer.Tools {
    override fun call(name: String, arguments: JsonObject): McpServer.Tools.Result = error("проба не должна звать инструменты")
  }

  @BeforeEach fun setUp() {
    api = VibeHttpApi(tokenProvider = { token }, runner = NoRuns(), mcpTools = NoTools(), productVersion = { "0.3.0" })
      .also { it.start(0) }
  }

  @AfterEach fun tearDown() { api.stop() }

  @Test
  fun `our own endpoint passes the probe`() {
    assertEquals(McpSelfProbe.Result.Ok, McpSelfProbe.run(api.boundPort, token))
  }

  @Test
  fun `without a token the probe says so instead of knocking`() {
    assertEquals(McpSelfProbe.Result.NoToken, McpSelfProbe.run(api.boundPort, null))
    assertEquals(McpSelfProbe.Result.NoToken, McpSelfProbe.run(api.boundPort, ""))
  }

  @Test
  fun `a token the listener does not know is a wrong answer, not a pass`() {
    val result = assertIs<McpSelfProbe.Result.WrongAnswer>(McpSelfProbe.run(api.boundPort, "not-the-token"))
    assertEquals(McpSelfProbe.Check.DISCOVER, result.check)
    assertEquals(401, result.status)
  }

  @Test
  fun `a closed port is unreachable`() {
    val port = api.boundPort
    api.stop()
    assertIs<McpSelfProbe.Result.Unreachable>(McpSelfProbe.run(port, token))
  }

  @Test
  fun `verdicts are read from the status and the body`() {
    assertNull(McpSelfProbe.checkDiscover(200, """{"result":{"supportedVersions":["2026-07-28","2025-06-18"]}}"""))
    // The field under its old name is exactly the defect the probe exists to catch.
    assertEquals(McpSelfProbe.Check.DISCOVER,
                 McpSelfProbe.checkDiscover(200, """{"result":{"protocolVersions":["2026-07-28"]}}""")?.check)
    assertEquals(503, McpSelfProbe.checkDiscover(503, """{"error":{"code":-32603}}""")?.status)

    assertNull(McpSelfProbe.checkMismatch(400, """{"error":{"code":-32020}}"""))
    assertEquals(200, McpSelfProbe.checkMismatch(200, """{"result":{"tools":[]}}""")?.status,
                 "заголовок, который не сверили, — это ответ 200")
    assertEquals(-32601, McpSelfProbe.checkMismatch(400, """{"error":{"code":-32601}}""")?.code)
    assertEquals(McpSelfProbe.Check.HEADER_MISMATCH, McpSelfProbe.checkMismatch(400, "не json")?.check)
  }
}
