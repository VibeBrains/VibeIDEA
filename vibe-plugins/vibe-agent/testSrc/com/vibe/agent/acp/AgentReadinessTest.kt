// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** «Чем платим» — the answer that otherwise arrives with the bill at the end of the month. */
class AgentReadinessTest {
  private val claude = AgentServerConfig("Claude Code", "npx", listOf("-y", "@agentclientprotocol/claude-agent-acp"), emptyMap())

  private fun check(agent: AgentServerConfig = claude, env: Map<String, String> = emptyMap()) =
    AgentReadiness.check(listOf(agent), onPath = { true }, env = { env[it] })

  @Test
  fun `the first billing source in Claude Code's own order is the one named`() {
    // code.claude.com/docs/en/authentication: USE_* → AUTH_TOKEN → API_KEY → … → subscription.
    val all = check(env = mapOf("ANTHROPIC_API_KEY" to "sk-x", "ANTHROPIC_AUTH_TOKEN" to "t", "CLAUDE_CODE_USE_BEDROCK" to "1"))
    assertEquals(AgentReadiness.Kind.SUBSCRIPTION_OVERRIDDEN, all.single().kind)
    assertEquals("CLAUDE_CODE_USE_BEDROCK", all.single().detail)
    assertEquals("ANTHROPIC_AUTH_TOKEN", check(env = mapOf("ANTHROPIC_API_KEY" to "sk-x", "ANTHROPIC_AUTH_TOKEN" to "t")).single().detail)
    assertEquals("ANTHROPIC_API_KEY", check(env = mapOf("ANTHROPIC_API_KEY" to "sk-x")).single().detail)
  }

  @Test
  fun `a cloud switch set to off is not a switch`() {
    for (off in listOf("0", "false", "no", "OFF", " ")) {
      assertTrue(check(env = mapOf("CLAUDE_CODE_USE_VERTEX" to off)).isEmpty(), off)
    }
  }

  @Test
  fun `an empty value in the entry takes the variable away, a set one brings it in`() {
    // Our own seed advises the empty value: that advice must not raise a warning.
    val cleared = claude.copy(env = mapOf("ANTHROPIC_API_KEY" to ""))
    assertTrue(check(cleared, env = mapOf("ANTHROPIC_API_KEY" to "sk-x")).isEmpty())
    val tokened = claude.copy(env = mapOf("ANTHROPIC_AUTH_TOKEN" to "tok"))
    assertEquals("ANTHROPIC_AUTH_TOKEN", check(tokened).single().detail)
  }

  @Test
  fun `other agents are not asked what pays for Claude`() {
    val gemini = AgentServerConfig("Gemini CLI", "gemini", listOf("--experimental-acp"), emptyMap())
    assertTrue(check(gemini, env = mapOf("ANTHROPIC_API_KEY" to "sk-x")).isEmpty())
  }

  @Test
  fun `a missing runtime is named with its command`() {
    val notice = AgentReadiness.check(listOf(claude), onPath = { false }, env = { null }).single()
    assertEquals(AgentReadiness.Kind.RUNTIME_MISSING, notice.kind)
    assertEquals("npx", notice.detail)
  }
}
