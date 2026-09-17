// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A pinned agent stays pinned, and the catalog says when the registry has moved on. */
class AgentRegistryUpgradeTest {
  private val catalog = AgentRegistry.parse("""
    { "agents": [
      { "id": "claude-acp", "name": "Claude Agent", "version": "0.78.0", "license": "MIT",
        "license_url": "https://github.com/agentclientprotocol/claude-agent-acp/blob/main/LICENSE",
        "distribution": { "npx": { "package": "@agentclientprotocol/claude-agent-acp@0.78.0" } } },
      { "id": "uv", "name": "UV Agent", "version": "0.5.0", "distribution": { "uvx": { "package": "uv-agent==0.5.0" } } }
    ] }""")

  private fun npx(name: String, spec: String) = AgentServerConfig(name, "npx", listOf("-y", spec), emptyMap())

  @Test
  fun `versions compare by release numbers, and anything not comparable is not newer`() {
    assertTrue(AgentRegistry.isNewer("0.78.0", "0.77.0"))
    assertTrue(AgentRegistry.isNewer("1.10.0", "1.9.9"))
    assertTrue(AgentRegistry.isNewer("2.0", "1.99.1"))
    assertFalse(AgentRegistry.isNewer("0.77.0", "0.78.0"))
    assertFalse(AgentRegistry.isNewer("0.78.0", "0.78.0"))
    assertFalse(AgentRegistry.isNewer("0.78.0-beta.1", "0.78.0"))
    assertFalse(AgentRegistry.isNewer("latest", "0.1.0"))
  }

  @Test
  fun `only a pinned agent behind the registry is offered a move`() {
    val configured = listOf(
      npx("Claude Agent", "@agentclientprotocol/claude-agent-acp@0.77.0"),
      npx("Claude Agent (web)", "@agentclientprotocol/claude-agent-acp"),
      npx("Ahead", "@agentclientprotocol/claude-agent-acp@0.79.0"),
      AgentServerConfig("UV", "uvx", listOf("uv-agent==0.4.0"), emptyMap()),
    )
    val upgrades = AgentRegistry.upgrades(catalog, configured)
    assertEquals(listOf("Claude Agent", "UV"), upgrades.map { it.agentName })
    assertEquals("@agentclientprotocol/claude-agent-acp@0.78.0", upgrades[0].toSpec)
    assertEquals("uv-agent==0.5.0", upgrades[1].toSpec)
    assertEquals("https://github.com/agentclientprotocol/claude-agent-acp/blob/main/LICENSE", catalog.first().licenseUrl)
  }

  @Test
  fun `the move rewrites only the version of the named entry, and says what is elsewhere`() {
    val existing = """{"agent_servers":{"Claude Agent":{"command":"npx","args":["-y","@agentclientprotocol/claude-agent-acp@0.77.0"],"env":{"A":"1"}},"Other":{"command":"x","args":[]}},"keep":true}"""
    val upgrades = AgentRegistry.upgrades(catalog, listOf(
      npx("Claude Agent", "@agentclientprotocol/claude-agent-acp@0.77.0"),
      npx("Project Agent", "@agentclientprotocol/claude-agent-acp@0.70.0"),
    ))
    val written = assertIs<AcpConfigWriter.Result.Written>(AcpConfigWriter.upgrade(existing, upgrades))
    assertEquals(listOf("Claude Agent"), written.added)
    val root = Json.parseToJsonElement(written.text).jsonObject
    val claude = root["agent_servers"]!!.jsonObject["Claude Agent"]!!.jsonObject
    assertEquals(listOf("-y", "@agentclientprotocol/claude-agent-acp@0.78.0"), claude["args"]!!.jsonArray.map { it.jsonPrimitive.content })
    assertEquals("1", claude["env"]!!.jsonObject["A"]!!.jsonPrimitive.content)
    assertTrue(root.containsKey("keep") && root["agent_servers"]!!.jsonObject.containsKey("Other"))
    assertTrue("Project Agent" in AcpConfigWriter.upgradeSnippet(upgrades.filter { it.agentName !in written.added }))
  }

  @Test
  fun `a file with comments is not rewritten, the change comes back as lines to make by hand`() {
    val upgrades = AgentRegistry.upgrades(catalog, listOf(npx("Claude Agent", "@agentclientprotocol/claude-agent-acp@0.77.0")))
    val refused = assertIs<AcpConfigWriter.Result.Refused>(AcpConfigWriter.upgrade("""{ // mine
      "agent_servers": {} }""", upgrades))
    assertTrue("0.77.0" in refused.snippet && "0.78.0" in refused.snippet)
  }
}
