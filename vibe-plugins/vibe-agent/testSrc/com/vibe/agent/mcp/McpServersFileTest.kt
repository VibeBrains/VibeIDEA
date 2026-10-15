// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.mcp

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Разбор `mcp.json`.
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

  @Test
  fun `the working folder is read as written, and a blank or a non-string one is none`() {
    val parsed = McpServersFile.parse("""
      {"mcpServers": {
        "a": {"command": "srv", "cwd": "/Users/me"},
        "b": {"command": "srv", "cwd": "  "},
        "c": {"command": "srv", "cwd": 5},
        "d": {"command": "srv"}
      }}
    """.trimIndent())
    assertEquals(listOf("/Users/me", null, null, null), parsed.servers.map { it.cwd })
  }

  @Test
  fun `an entry by address is passed in silence, and one with both a command and an address is named`() {
    val parsed = McpServersFile.parse("""
      {"mcpServers": {
        "tracker": {"url": "https://mcp.example.com/mcp", "type": "http"},
        "both": {"command": "node", "url": "https://mcp.example.com/mcp"},
        "empty": {"url": "  "},
        "ok": {"command": "node"}
      }}
    """.trimIndent())
    assertEquals(listOf("ok"), parsed.servers.map { it.name })
    assertEquals(listOf("tracker"), parsed.remote)
    assertEquals(
      listOf(McpServersFile.Complaint(McpServersFile.Problem.AMBIGUOUS, "both"), McpServersFile.Complaint(McpServersFile.Problem.NO_COMMAND, "empty")),
      parsed.problems,
    )
  }

  @Test
  fun `a command that is not a string, and arguments that are not scalars, are read without a throw`() {
    val parsed = McpServersFile.parse("""
      {"mcpServers": {
        "num": {"command": 5},
        "ok": {"command": "srv", "args": ["a", 1, null, ["x"], {"k": "v"}], "env": {"A": "1", "B": [1], "C": null, "D": true}}
      }}
    """.trimIndent())
    assertEquals(listOf(McpServersFile.Complaint(McpServersFile.Problem.NO_COMMAND, "num")), parsed.problems)
    assertEquals(listOf("a", "1"), parsed.servers.single().args)
    assertEquals(mapOf("A" to "1", "D" to "true"), parsed.servers.single().env)
  }

  private fun parsed(vararg entries: McpServersFile.Entry, remote: List<String> = emptyList()) =
    McpServersFile.Parsed(entries.toList(), emptyList(), remote)

  @Test
  fun `the project entry replaces the shared one whole, and the others of both files stay`() {
    val user = parsed(McpServersFile.Entry("a", "node", listOf("a.js")), McpServersFile.Entry("b", "node", env = mapOf("K" to "v")))
    val project = parsed(McpServersFile.Entry("b", "npx", listOf("pkg")), McpServersFile.Entry("c", "uvx"))
    val merged = McpServersFile.merge(user, project)
    assertEquals(listOf("a", "b", "c"), merged.servers.map { it.name })
    assertEquals(McpServersFile.Entry("b", "npx", listOf("pkg")), merged.servers[1], "no field of the shared b survives")
    assertEquals(
      mapOf("a" to McpServersFile.Layer.USER, "b" to McpServersFile.Layer.PROJECT, "c" to McpServersFile.Layer.PROJECT), merged.sourceOfName)
    assertEquals(setOf("b"), merged.overrides)
  }

  @Test
  fun `names are compared exactly, and a name by address shadows a command of the same name`() {
    val merged = McpServersFile.merge(
      parsed(McpServersFile.Entry("GitHub", "node"), McpServersFile.Entry("t", "node")),
      parsed(McpServersFile.Entry("github", "npx"), remote = listOf("t")),
    )
    assertEquals(listOf("GitHub", "github"), merged.servers.map { it.name })
    assertEquals(listOf("t"), merged.remote)
    assertEquals(setOf("t"), merged.overrides)
    assertEquals(mapOf("USER" to setOf("GitHub", "t"), "PROJECT" to setOf("github", "t")), merged.names.mapKeys { it.key.name })
  }

  @Test
  fun `a broken file of one layer leaves the servers of the other`(@TempDir dir: Path) {
    val home = Files.createDirectory(dir.resolve("home"))
    val project = Files.createDirectory(dir.resolve("project"))
    Files.createDirectories(McpServersFile.userPath(home.toString()).parent)
    Files.writeString(McpServersFile.userPath(home.toString()), """{"mcpServers": {"a": {"command": "node"}}}""")
    Files.createDirectories(McpServersFile.pathIn(project.toString())!!.parent)
    Files.writeString(McpServersFile.pathIn(project.toString())!!, "{ не json")
    val merged = McpServersFile.loadLayers(project.toString(), home.toString())
    assertEquals(listOf("a"), merged.servers.map { it.name })
    assertEquals(listOf(McpServersFile.Layer.PROJECT to McpServersFile.Complaint(McpServersFile.Problem.NOT_JSON)), merged.allProblems)
  }

  @Test
  fun `no file is no complaint, and a project without a root has only the shared file`(@TempDir dir: Path) {
    assertEquals(McpServersFile.Merged(emptyList(), emptyList(), emptyMap(), emptySet(),
                                       mapOf(McpServersFile.Layer.USER to emptyList(), McpServersFile.Layer.PROJECT to emptyList()),
                                       mapOf(McpServersFile.Layer.USER to emptySet(), McpServersFile.Layer.PROJECT to emptySet())),
                 McpServersFile.loadLayers(null, dir.toString()))
    Files.createDirectories(McpServersFile.userPath(dir.toString()).parent)
    Files.writeString(McpServersFile.userPath(dir.toString()), """{"mcpServers": {"a": {"command": "node"}}}""")
    assertEquals(listOf("a"), McpServersFile.loadLayers(null, dir.toString()).servers.map { it.name })
    assertNull(McpServersFile.pathOf(McpServersFile.Layer.PROJECT, null, dir.toString()))
  }

  @Test
  fun `a complaint is worded with the file it is about`() {
    val project = McpServersFile.Complaint(McpServersFile.Problem.AMBIGUOUS, "both")
    assertEquals(".vibe/mcp.json: запись «both»: указаны и command, и url — оставьте одно, иначе два продукта запустят разное",
                 McpFileText.line(McpServersFile.Layer.PROJECT, project))
    assertTrue(McpFileText.line(McpServersFile.Layer.USER, McpServersFile.Complaint(McpServersFile.Problem.NOT_JSON)).startsWith("~/.vibe/mcp.json: "))
  }
}
