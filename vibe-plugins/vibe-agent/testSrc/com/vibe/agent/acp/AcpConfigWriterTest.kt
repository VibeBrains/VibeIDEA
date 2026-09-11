// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Adding from the catalog must never cost the person what they wrote by hand. */
class AcpConfigWriterTest {
  private val agent = AgentServerConfig("UV Agent", "uvx", listOf("uv-agent"), emptyMap())
  private val default = AgentServerConfig("Claude Code", "npx", listOf("-y", "@agentclientprotocol/claude-agent-acp"), emptyMap())

  private fun servers(text: String) = Json.parseToJsonElement(text).jsonObject["agent_servers"]!!.jsonObject

  @Test
  fun `no file becomes a file with the agent`() {
    val written = assertIs<AcpConfigWriter.Result.Written>(AcpConfigWriter.add(null, listOf(agent)))
    assertEquals(listOf("UV Agent"), written.added)
    val entry = servers(written.text)["UV Agent"]!!.jsonObject
    assertEquals("uvx", entry["command"]!!.jsonPrimitive.content)
    assertEquals(listOf("uv-agent"), entry["args"]!!.jsonArray.map { it.jsonPrimitive.content })
  }

  @Test
  fun `the first entry of the file does not make the default agent vanish`() {
    // A file with agents replaces the default list: without this, adding one agent took Claude Code away.
    val written = assertIs<AcpConfigWriter.Result.Written>(AcpConfigWriter.add(null, listOf(agent), listOf(default)))
    assertEquals(listOf("Claude Code", "UV Agent"), servers(written.text).keys.toList())
    assertEquals(listOf("Claude Code"), written.defaults)
    val later = assertIs<AcpConfigWriter.Result.Written>(
      AcpConfigWriter.add("""{ "agent_servers": { "Mine": { "command": "m" } } }""", listOf(agent), listOf(default)))
    assertTrue(later.defaults.isEmpty(), "a file with agents replaced the default already — bringing it back is not ours to decide")
  }

  @Test
  fun `an existing plain file keeps its entries and its other keys`() {
    val existing = """{ "agent_servers": { "Mine": { "command": "mine" } }, "default_agent": "Mine" }"""
    val written = assertIs<AcpConfigWriter.Result.Written>(AcpConfigWriter.add(existing, listOf(agent)))
    assertEquals(setOf("Mine", "UV Agent"), servers(written.text).keys)
    assertEquals("Mine", Json.parseToJsonElement(written.text).jsonObject["default_agent"]!!.jsonPrimitive.content)
  }

  @Test
  fun `a file with comments or trailing commas is left alone and the snippet comes back`() {
    for (existing in listOf("// мои агенты\n{ \"agent_servers\": {} }", "{ \"agent_servers\": { \"Mine\": { \"command\": \"m\" }, } }")) {
      val refused = assertIs<AcpConfigWriter.Result.Refused>(AcpConfigWriter.add(existing, listOf(agent)), existing)
      assertTrue("\"UV Agent\"" in refused.snippet, refused.snippet)
    }
  }

  @Test
  fun `agent_servers of another shape is not overwritten`() {
    assertIs<AcpConfigWriter.Result.Refused>(AcpConfigWriter.add("""{ "agent_servers": [] }""", listOf(agent)))
  }

  @Test
  fun `an agent already in the file is not added twice`() {
    val existing = """{ "agent_servers": { "UV Agent": { "command": "uvx", "args": ["uv-agent"] } } }"""
    val written = assertIs<AcpConfigWriter.Result.Written>(AcpConfigWriter.add(existing, listOf(agent)))
    assertTrue(written.added.isEmpty())
  }
}
