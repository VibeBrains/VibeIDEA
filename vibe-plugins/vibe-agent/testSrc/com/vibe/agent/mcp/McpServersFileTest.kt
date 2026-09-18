// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Разбор `.vibe/mcp.json`.
 *
 * Главное здесь не «читается ли правильный файл», а что делает НЕПРАВИЛЬНЫЙ: сервер, пропавший
 * молча, выглядит как сломанный инструмент, и человек ищет ошибку в IDE, а не в своей опечатке.
 */
class McpServersFileTest {
  @Test
  fun `the usual shape is read, the way other tools write it`() {
    val parsed = McpServersFile.parse("""
      {"mcpServers": {
        "github": {"command": "npx", "args": ["-y", "@modelcontextprotocol/server-github"],
                   "env": {"GITHUB_TOKEN": "x"}},
        "старый": {"command": "node", "args": ["server.js"], "disabled": "true"}
      }}
    """.trimIndent())
    assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
    assertEquals(listOf("github", "старый"), parsed.servers.map { it.name })
    assertEquals(listOf("-y", "@modelcontextprotocol/server-github"), parsed.servers.first().args)
    assertEquals(mapOf("GITHUB_TOKEN" to "x"), parsed.servers.first().env)
    assertTrue(parsed.servers.last().disabled)
  }

  @Test
  fun `an entry without a command is named, and the others still work`() {
    val parsed = McpServersFile.parse("""{"mcpServers": {"пустой": {}, "рабочий": {"command": "node"}}}""")
    assertEquals(listOf("рабочий"), parsed.servers.map { it.name })
    assertEquals(McpServersFile.Problem.NO_COMMAND, parsed.problems.single().problem)
    assertEquals("пустой", parsed.problems.single().name)
  }

  @Test
  fun `a broken file complains instead of pretending there are no servers`() {
    assertTrue(McpServersFile.parse("это не json").problems.isNotEmpty())
    assertTrue(McpServersFile.parse("""{"нет": "секции"}""").problems.isNotEmpty())
  }

  @Test
  fun `the ACP entry carries env as pairs, as the schema requires`() {
    val entry = McpServersFile.acpEntry(McpServersFile.Entry("x", "node", listOf("a"), mapOf("K" to "V")))
    assertEquals("stdio", entry["type"])
    assertEquals(listOf(mapOf("name" to "K", "value" to "V")), entry["env"])
  }
}
