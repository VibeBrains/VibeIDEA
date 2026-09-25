// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.acp.AgentSecrets
import com.vibe.agent.security.SecretPatterns
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A team's memory on the VibeMemory host: where the machine keeps what `vibememory connect` issued, how the tools are
 * named beside the local memory, and that the token given to an agent is never printed back
 */
class TeamMemoryTest {
  private fun sidecar(team: String = "acme", url: String = "https://vibememory.ru/mcp", agent: String = "vibeidea") =
    """{"cabinet":"https://app.vibememory.ru","team":"$team","member":"m1","agent":"$agent","tokenId":"tk_1","mcpUrl":"$url"}"""

  @Test
  fun `a sidecar names the team and the server, never the token`() {
    val team = TeamMemory.parseSidecar(sidecar(), "acme")!!
    assertEquals(TeamMemory.Team("acme", "https://vibememory.ru/mcp", "https://app.vibememory.ru", "tk_1"), team)
  }

  @Test
  fun `a sidecar that is not ours or not usable is skipped`() {
    assertNull(TeamMemory.parseSidecar(sidecar(agent = "claude-code"), "acme"), "another agent's token")
    assertNull(TeamMemory.parseSidecar(sidecar(team = "Acme Corp"), "acme"), "not a team name VibeMemory issues")
    assertNull(TeamMemory.parseSidecar("""{"team":"acme"}""", "acme"), "no server address")
    // The token must not cross a network in the clear; a server on this machine is the local test setup
    assertNull(TeamMemory.parseSidecar(sidecar(url = "http://vibememory.ru/mcp"), "acme"))
    assertEquals("http://127.0.0.1:7171/mcp", TeamMemory.parseSidecar(sidecar(url = "http://127.0.0.1:7171/mcp"), "acme")?.url)
  }

  @Test
  fun `teams are read from the tokens directory, one per sidecar`() {
    val root = Files.createTempDirectory("vibememory-root")
    fun write(team: String, text: String) {
      Files.createDirectories(root.resolve("tokens/$team"))
      Files.writeString(root.resolve("tokens/$team/vibeidea.json"), text)
    }
    write("beta", sidecar(team = "beta"))
    write("acme", sidecar())
    write("broken", "{ not json")
    Files.createDirectories(root.resolve("tokens/other"))
    Files.writeString(root.resolve("tokens/other/claude-code.json"), sidecar(team = "other", agent = "claude-code"))
    assertEquals(listOf("acme", "beta"), TeamMemory.teams(root).map { it.team })
    assertEquals(emptyList(), TeamMemory.teams(Files.createTempDirectory("empty-root")))
  }

  @Test
  fun `the helper prints one object of headers, anything else is a failure`() {
    assertEquals(mapOf("Authorization" to "Bearer vmt_1_x"), TeamMemory.parseHeaders("""{"Authorization":"Bearer vmt_1_x"}""" + "\n"))
    assertFailsWith<Exception> { TeamMemory.parseHeaders("{}") }
    assertFailsWith<Exception> { TeamMemory.parseHeaders("""{"Authorization":{"x":1}}""") }
    assertFailsWith<Exception> { TeamMemory.parseHeaders("usage: vibememory mcp-headers <team> <agent>") }
  }

  @Test
  fun `the helper is run with the team and this product's agent name`() {
    if (System.getProperty("os.name").lowercase().contains("win")) return
    val dir = Files.createTempDirectory("vibememory-helper")
    val helper = dir.resolve("vibememory")
    Files.writeString(helper, "#!/bin/sh\n[ \"\$1 \$2 \$3\" = \"mcp-headers acme vibeidea\" ] || { echo \"bad args: \$*\" >&2; exit 2; }\n" +
                              "echo '{\"Authorization\":\"Bearer vmt_1_secretsecretsecret\"}'\n")
    helper.toFile().setExecutable(true)
    assertEquals(mapOf("Authorization" to "Bearer vmt_1_secretsecretsecret"), TeamMemory.headers(helper, "acme"))
    val error = assertFailsWith<McpClient.McpException> { TeamMemory.headers(helper, "beta") }
    assertTrue(error.message!!.contains("bad args"), error.message)
    assertFailsWith<McpClient.McpException> { TeamMemory.headers(dir.resolve("missing"), "acme") }
  }

  @Test
  fun `a team's tools carry the team, so they neither shadow nor get shadowed by the local memory`() {
    assertEquals("team-acme__memory_save", TeamMemory.toolName("acme", "memory_save"))
    assertEquals("memory_save", TeamMemory.serverTool("acme", "team-acme__memory_save"))
    assertNull(TeamMemory.serverTool("acme", "memory_save"))
    assertNull(TeamMemory.serverTool("acme", "team-beta__memory_save"))
    assertNull(TeamMemory.serverTool("acme", "team-acme__"))
  }

  /** A server scripted in memory: the transport is the seam, so the source is tested without a network */
  private class ScriptedTransport(private val refuse: () -> Boolean = { false }) : McpTransport {
    val calls = ArrayList<String>()
    override var isAlive = true
    override fun exchange(message: JsonObject, id: Long, timeoutMs: Long): JsonObject {
      if (refuse()) throw McpClient.Unauthorized(McpHttpTransport.UNAUTHORIZED)
      val method = message["method"]!!.jsonPrimitive.content
      val result = when (method) {
        "tools/list" -> """{"tools":[{"name":"memory_search","description":"Search"},{"name":"memory_save","description":"Save"}]}"""
        "tools/call" -> {
          calls += (message["params"] as JsonObject)["name"]!!.jsonPrimitive.content
          """{"content":[{"type":"text","text":"ok"}]}"""
        }
        "initialize" -> """{"protocolVersion":"2025-06-18"}"""
        else -> return buildJsonObject { put("error", buildJsonObject { put("code", -32601); put("message", "no") }) }
      }
      return buildJsonObject { put("result", kotlinx.serialization.json.Json.parseToJsonElement(result).jsonObject) }
    }
    override fun notify(message: JsonObject) {}
    override fun close() { isAlive = false }
  }

  @Test
  fun `the source offers each team's tools renamed and routes a call back to its team`() {
    val acme = ScriptedTransport()
    val beta = ScriptedTransport()
    val teams = listOf(TeamMemory.Team("acme", "https://h/mcp", null, null), TeamMemory.Team("beta", "https://h/mcp", null, null))
    val source = TeamMemorySource({ teams }, { team -> McpClient(if (team.team == "acme") acme else beta) }, "test")
    val specs = source.specs()
    assertEquals(listOf("team-acme__memory_search", "team-acme__memory_save", "team-beta__memory_search", "team-beta__memory_save"),
                 specs.map { it.name })
    assertTrue(specs.first().description.contains("\"acme\""), specs.first().description)
    assertEquals(McpProtocol.Risk.READ, source.riskOf("team-acme__memory_search"))
    assertEquals(McpProtocol.Risk.WRITE, source.riskOf("team-beta__memory_save"))
    assertEquals(McpProtocol.Risk.WRITE, source.riskOf("memory_save"), "a name that is not a team's is treated as writing")
    assertEquals("ok", source.call("team-beta__memory_save", buildJsonObject { put("project", "p") }).text)
    assertEquals(listOf("memory_save"), beta.calls)
    assertEquals(emptyList(), acme.calls)
  }

  @Test
  fun `a refused token is reported by team and the others keep their tools`() {
    val failures = ArrayList<Pair<String, Exception>>()
    val teams = listOf(TeamMemory.Team("acme", "https://h/mcp", null, null), TeamMemory.Team("beta", "https://h/mcp", null, null))
    val source = TeamMemorySource({ teams }, { team -> McpClient(ScriptedTransport { team.team == "acme" }) }, "test",
                                  onFailure = { team, e -> failures += team.team to e })
    assertEquals(listOf("team-beta__memory_search", "team-beta__memory_save"), source.specs().map { it.name })
    assertEquals("acme", failures.single().first)
    assertTrue(failures.single().second is McpClient.Unauthorized)
  }

  @Test
  fun `the agent's record of a team server has headers as name-value pairs`() {
    val entry = TeamMemory.acpEntry(TeamMemory.Team("acme", "https://vibememory.ru/mcp", null, null),
                                    mapOf("Authorization" to "Bearer vmt_1_secretsecretsecret"))
    assertEquals("http", entry["type"])
    assertEquals("vibememory-acme", entry["name"])
    assertEquals(listOf(mapOf("name" to "Authorization", "value" to "Bearer vmt_1_secretsecretsecret")), entry["headers"])
  }

  @Test
  fun `a token handed to an agent is masked in whatever it prints`() {
    val entry = TeamMemory.acpEntry(TeamMemory.Team("acme", "https://h/mcp", null, null),
                                    mapOf("Authorization" to "Bearer legacy-token-without-a-shape"))
    val secrets = AgentSecrets.headerValues(listOf(entry, mapOf("name" to "vibememory", "command" to "/bin/x")))
    val line = "config: {\"Authorization\":\"Bearer legacy-token-without-a-shape\"} token=legacy-token-without-a-shape"
    val masked = AgentSecrets.maskValues(line, secrets)
    assertFalse(masked.contains("legacy-token-without-a-shape"), masked)
    // A token of VibeMemory's own form is known by shape, even when we never handed it out
    assertFalse(SecretPatterns.redact("got vmt_ab12_Zx9-secretsecretsecret here").contains("secretsecretsecret"))
  }
}
