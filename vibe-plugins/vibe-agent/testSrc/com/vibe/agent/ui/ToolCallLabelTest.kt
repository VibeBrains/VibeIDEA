// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import com.vibe.agent.mcp.McpProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Подпись вызова инструмента в ленте.
 *
 * Разбор аргументов идёт по тексту, а приезжают они кусками и бывают оборванными — поэтому здесь
 * проверяется не красота, а то, что подпись не падает и не врёт.
 */
class ToolCallLabelTest {
  @Test
  fun `our tool is named as the catalogue names it`() {
    val label = ToolCallLabel.of(McpProtocol.TOOL_READ_FILE, """{"path":"src/main.ts"}""")
    assertTrue(label.startsWith(McpProtocol.titleOf(McpProtocol.TOOL_READ_FILE)!!), label)
    assertTrue(label.endsWith("src/main.ts"), label)
  }

  @Test
  fun `a foreign tool keeps its own name`() {
    assertEquals("memory_search", ToolCallLabel.of("memory_search", null))
  }

  @Test
  fun `broken arguments do not break the label`() {
    assertEquals(McpProtocol.titleOf(McpProtocol.TOOL_READ_FILE), ToolCallLabel.of(McpProtocol.TOOL_READ_FILE, """{"path":"""))
  }

  @Test
  fun `a long path is cut from the front, a long query from the back`() {
    val path = "a/" + "b".repeat(200)
    assertTrue(ToolCallLabel.subjectOf("""{"path":"$path"}""")!!.startsWith("…"))
    assertTrue(ToolCallLabel.subjectOf("""{"query":"${"c".repeat(200)}"}""")!!.endsWith("…"))
  }
}
