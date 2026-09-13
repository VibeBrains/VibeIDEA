// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.mcp.McpAccess.Verdict
import com.vibe.agent.mcp.McpProtocol.Risk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What MCP tools may do: project trust first, then the class switches.
 *
 * Guards two defects at once. `/mcp` did not ask for trust while hooks in the same project refused;
 * and turning on the HTTP API for the import graph silently handed out agent runs.
 */
class McpAccessTest {
  private fun verdict(risk: Risk, trusted: Boolean, write: Boolean = false, execute: Boolean = false) =
    McpAccess.verdict(risk, trusted, write, execute)

  @Test
  fun `reading is always allowed`() {
    assertEquals(Verdict.ALLOWED, verdict(Risk.READ, trusted = false))
    assertEquals(Verdict.ALLOWED, verdict(Risk.READ, trusted = true))
  }

  @Test
  fun `untrusted project stays read-only even with both switches on`() {
    assertEquals(Verdict.UNTRUSTED, verdict(Risk.WRITE, trusted = false, write = true, execute = true))
    assertEquals(Verdict.UNTRUSTED, verdict(Risk.EXECUTE, trusted = false, write = true, execute = true))
  }

  @Test
  fun `in a trusted project writing and executing are off by default`() {
    assertEquals(Verdict.DISABLED, verdict(Risk.WRITE, trusted = true))
    assertEquals(Verdict.DISABLED, verdict(Risk.EXECUTE, trusted = true))
  }

  @Test
  fun `each switch opens only its own class`() {
    assertEquals(Verdict.ALLOWED, verdict(Risk.WRITE, trusted = true, write = true))
    assertEquals(Verdict.DISABLED, verdict(Risk.EXECUTE, trusted = true, write = true))
    assertEquals(Verdict.ALLOWED, verdict(Risk.EXECUTE, trusted = true, execute = true))
    assertEquals(Verdict.DISABLED, verdict(Risk.WRITE, trusted = true, execute = true))
  }

  @Test
  fun `settings defaults keep writing and executing closed`() {
    assertEquals(false, com.vibe.agent.settings.VibeAgentSettings.DEFAULT_MCP_ALLOW_WRITE)
    assertEquals(false, com.vibe.agent.settings.VibeAgentSettings.DEFAULT_MCP_ALLOW_EXECUTE)
  }

  @Test
  fun `agent run and decision record are declared dangerous`() {
    assertEquals(Risk.EXECUTE, McpProtocol.riskOf(McpProtocol.TOOL_RUN))
    assertEquals(Risk.WRITE, McpProtocol.riskOf(McpProtocol.TOOL_DECISIONS_RECORD))
  }

  @Test
  fun `every listed tool except the two dangerous ones is reading`() {
    // An unknown name falls to EXECUTE, so a tool forgotten in the list would silently stop working.
    // This forces a deliberate decision for every name in the listing.
    val names = McpProtocol.TOOLS.map { it.name }
    assertEquals(names.size - 2, names.count { McpProtocol.riskOf(it) == Risk.READ })
  }

  @Test
  fun `unknown tool is treated as the most dangerous`() {
    assertEquals(Risk.EXECUTE, McpProtocol.riskOf("vibe_something_new"))
  }

  @Test
  fun `each refusal names where it is lifted`() {
    assertNull(McpAccess.refusal(Verdict.ALLOWED))
    val untrusted = McpAccess.refusal(Verdict.UNTRUSTED)!!
    val disabled = McpAccess.refusal(Verdict.DISABLED)!!
    assertTrue("Trust Project" in untrusted, untrusted)
    assertTrue("Settings" in disabled, disabled)
    assertNotEquals(untrusted, disabled)
  }
}
