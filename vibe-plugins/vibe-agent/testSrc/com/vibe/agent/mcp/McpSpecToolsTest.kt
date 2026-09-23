// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.help.HelpBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The tools table of the MCP spec against the catalogue the server lists.
 *
 * The spec is written for a model to read whole, so a tool missing from its table is a tool the model never learns
 * about, and a wrong access class tells a person that a switch opens less than it does. The bundled copy is read: the
 * docs gate holds it equal to docs/vibe.
 */
class McpSpecToolsTest {
  private val rows: Map<String, String> by lazy {
    val text = assertNotNull(HelpBundle.read(HelpBundle.ROOT + "/manuals/mcpSpec.md"), "mcpSpec.md is not in the bundle")
    val section = text.substringAfter("\n## Инструменты\n", "").substringBefore("\n## ")
    ROW.findAll(section).associate { it.groupValues[1] to it.groupValues[2] }
  }

  @Test
  fun `every tool the server lists has a row, and every row is a tool the server lists`() {
    val listed = McpProtocol.TOOLS.map { it.name }.toSet()
    assertEquals(emptySet(), listed - rows.keys, "tools missing from the spec table")
    assertEquals(emptySet(), rows.keys - listed, "rows for tools the server does not list")
  }

  @Test
  fun `each row states the access class the server enforces`() {
    val wrong = McpProtocol.TOOLS.map { it.name }.filter { rows[it] != CLASS.getValue(McpProtocol.riskOf(it)) }
    assertTrue(wrong.isEmpty(), wrong.joinToString { "$it: «${rows[it]}», expected «${CLASS.getValue(McpProtocol.riskOf(it))}»" })
  }

  private companion object {
    /** A row of the tools table: the tool name in backticks, then its access class. */
    val ROW = Regex("""^\|\s*`(vibe_[a-z_]+)`\s*\|\s*`?([^|`]+?)`?\s*\|""", RegexOption.MULTILINE)

    /** The words the spec uses for the three classes. */
    val CLASS = mapOf(
      McpProtocol.Risk.READ to "чтение",
      McpProtocol.Risk.WRITE to "запись",
      McpProtocol.Risk.EXECUTE to "запуск",
    )
  }
}
