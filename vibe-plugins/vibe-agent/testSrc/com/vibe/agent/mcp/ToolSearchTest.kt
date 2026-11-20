// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.providers.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tool search on our side: silent below the threshold, a search tool plus loaded tools above it. */
class ToolSearchTest {
  private fun tool(name: String, description: String) = ToolSpec(name, description, JsonObject(emptyMap()))

  private val catalogue = listOf(
    tool("vibe_symbol_usages", "Where an identifier is used across the project"),
    tool("memory_search", "Search saved memory records by words"),
    tool("vibe_decisions_record", "Record a decision in the project journal"),
    tool("vibe_code_graph_importers", "Files that import the given file"),
  )

  @Test
  fun `below the threshold every tool is offered and no search appears`() {
    assertEquals(catalogue, ToolSearch.offered(catalogue, loaded = emptySet(), threshold = 4))
  }

  @Test
  fun `above the threshold only the search and the loaded tools are offered`() {
    val offered = ToolSearch.offered(catalogue, loaded = setOf("memory_search"), threshold = 3)
    assertEquals(listOf(ToolSearch.NAME, "memory_search"), offered.map { it.name })
  }

  @Test
  fun `the name weighs more than the description`() {
    val found = ToolSearch.search("search memory", catalogue)
    assertEquals("memory_search", found.first().name)
  }

  @Test
  fun `a query with no common word finds nothing and says so`() {
    assertTrue(ToolSearch.search("deploy kubernetes", catalogue).isEmpty())
    assertTrue(ToolSearch.answer(emptyList()).startsWith("No tool matches"))
  }

  @Test
  fun `the search never finds itself and respects the limit`() {
    val many = catalogue + ToolSearch.SPEC + List(10) { tool("project_tool_$it", "project helper") }
    val found = ToolSearch.search("project tool", many)
    assertEquals(ToolSearch.RESULTS, found.size)
    assertTrue(found.none { it.name == ToolSearch.NAME })
  }

  @Test
  fun `the agent's own hands are never deferred`() {
    val registry = listOf(
      tool(McpProtocol.TOOL_READ_FILE, "Read a file"),
      tool(McpProtocol.TOOL_WRITE_FILE, "Write a file"),
    ) + List(6) { tool("memory_tool_$it", "memory") }
    val offered = ToolSearch.offered(registry, loaded = emptySet(), threshold = 3).map { it.name }
    assertTrue(McpProtocol.TOOL_READ_FILE in offered)
    assertTrue(McpProtocol.TOOL_WRITE_FILE in offered)
    assertTrue("memory_tool_5" !in offered)
  }

  @Test
  fun `deferral takes the end of the registry first`() {
    val registry = listOf(
      tool("ide_first", "x"), tool("ide_second", "x"), tool("own_server_a", "x"), tool("own_server_b", "x"))
    val deferred = ToolSearch.deferredNames(registry, loaded = emptySet(), threshold = 2)
    assertEquals(setOf("own_server_a", "own_server_b"), deferred)
  }

  @Test
  fun `a loaded tool is not deferred again`() {
    val registry = listOf(tool("ide_first", "x"), tool("ide_second", "x"), tool("own_server_a", "x"))
    val offered = ToolSearch.offered(registry, loaded = setOf("own_server_a"), threshold = 2).map { it.name }
    assertTrue("own_server_a" in offered)
  }

  @Test
  fun `a heavy schema defers tools even when there are few of them`() {
    val blob = JsonPrimitive("y".repeat(ToolSearch.SCHEMA_CHARS_THRESHOLD))
    val heavy = ToolSpec("huge_tool", "x", JsonObject(mapOf("blob" to blob)))
    val registry = listOf(tool("ide_first", "x"), heavy)
    assertTrue(ToolSearch.active(registry, threshold = 100))
    val offered = ToolSearch.offered(registry, loaded = emptySet(), threshold = 100).map { it.name }
    assertEquals(listOf(ToolSearch.NAME, "ide_first"), offered)
  }

  @Test
  fun `the note names the deferred tools, and named tools are loaded by exact name`() {
    val registry = listOf(tool("ide_first", "x"), tool("own_server_a", "x"), tool("own_server_b", "x"))
    val offered = ToolSearch.offered(registry, loaded = emptySet(), threshold = 1)
    val note = ToolSearch.deferralNote(registry, offered)!!
    assertTrue(note.contains("<deferred_tools>") && note.contains("own_server_b"))
    assertTrue("ide_first" !in note)

    val found = ToolSearch.search("select:own_server_b,typo_tool", registry)
    assertEquals(listOf("own_server_b"), found.map { it.name })
    val asked = ToolSearch.selected("select:own_server_b,typo_tool")
    assertEquals(listOf("own_server_b", "typo_tool"), asked)
    assertTrue(ToolSearch.answer(found, missing = listOf("typo_tool")).startsWith("No such tool: typo_tool."))
  }

  @Test
  fun `nothing is deferred below the threshold, so there is no note`() {
    assertEquals(emptySet(), ToolSearch.deferredNames(catalogue, loaded = emptySet(), threshold = 10))
    assertEquals(null, ToolSearch.deferralNote(catalogue, catalogue))
  }
}
