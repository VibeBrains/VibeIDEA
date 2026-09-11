// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Sign-in is read as the agent declared it, and a terminal method becomes the exact command to run. */
class AgentAuthTest {
  private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

  @Test
  fun `methods are read as declared, a missing type is the protocol's agent`() {
    val methods = AgentAuth.methods(obj("""
      { "authMethods": [
        { "id": "agent-login", "name": "Agent login", "description": "the agent's own flow" },
        { "id": "claude-login", "name": "Log in with Claude", "type": "terminal", "args": ["--cli"], "env": { "A": "1" } },
        { "id": "vars", "name": "Env", "type": "env_var" },
        { "name": "no id" } ] }
    """.trimIndent()))
    assertEquals(listOf("agent-login", "claude-login", "vars"), methods.map { it.id })
    assertEquals(listOf(AuthMethod.Kind.AGENT, AuthMethod.Kind.TERMINAL, AuthMethod.Kind.OTHER), methods.map { it.kind })
    assertEquals(listOf("--cli"), methods[1].args)
    assertEquals(mapOf("A" to "1"), methods[1].env)
    assertEquals("env_var", methods[2].type)
    assertTrue(AgentAuth.methods(obj("{}")).isEmpty())
  }

  @Test
  fun `logout is supported only when declared as an object`() {
    assertTrue(AgentAuth.logoutSupported(obj("""{ "auth": { "logout": {} } }""")))
    assertFalse(AgentAuth.logoutSupported(obj("""{ "auth": { "logout": null } }""")))
    assertFalse(AgentAuth.logoutSupported(obj("""{ "auth": {} }""")))
    assertFalse(AgentAuth.logoutSupported(null))
  }

  @Test
  fun `auth_required is recognised through the futures that wrap it`() {
    val error = AcpRpcError(AgentAuth.AUTH_REQUIRED, """{"code":-32000,"message":"Authentication required"}""")
    assertTrue(AgentAuth.isAuthRequired(ExecutionException(CompletionException(error))))
    assertFalse(AgentAuth.isAuthRequired(ExecutionException(AcpRpcError(-32601, "no such method"))))
    assertFalse(AgentAuth.isAuthRequired(IllegalStateException("agent stopped")))
  }

  @Test
  fun `a terminal method is the agent's own command with the method's arguments and environment`() {
    val config = AgentServerConfig("Claude Code", "npx", listOf("-y", "@agentclientprotocol/claude-agent-acp"),
                                   mapOf("ANTHROPIC_API_KEY" to "", "TOKEN" to "\${secret:TOKEN}"))
    val method = AuthMethod("claude-login", "Log in with Claude", null, "terminal", AuthMethod.Kind.TERMINAL,
                            args = listOf("--cli", "auth login"), env = mapOf("MODE" to "it's"))
    val posix = AgentAuth.terminalCommand(config, method, windows = false)
    assertEquals("MODE='it'\\''s' npx -y @agentclientprotocol/claude-agent-acp --cli 'auth login'", posix.line)
    // The entry's own variables are named, never printed: their values can be keys.
    assertEquals(listOf("ANTHROPIC_API_KEY", "TOKEN"), posix.entryEnvNames)
    assertFalse("secret" in posix.line, posix.line)
    assertEquals("set \"MODE=it's\" && npx -y @agentclientprotocol/claude-agent-acp --cli \"auth login\"",
                 AgentAuth.terminalCommand(config, method, windows = true).line)
  }
}
