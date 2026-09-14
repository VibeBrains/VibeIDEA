// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.providers.ToolSpec
import kotlinx.serialization.json.JsonObject
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
}
