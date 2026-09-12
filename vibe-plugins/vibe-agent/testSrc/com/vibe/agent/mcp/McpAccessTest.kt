// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Доверие проекта на пути MCP.
 *
 * Гейт против возврата дефекта: хуки в недоверенном проекте отказывали, а `/mcp` пускал запуск
 * агента и запись файлов. Снаружи разница была невидима, поэтому её сторожит тест, а не память.
 */
class McpAccessTest {
  @Test
  fun `в недоверенном проекте читать можно`() {
    assertTrue(McpAccess.allowed(McpProtocol.Risk.READ, trusted = false))
  }

  @Test
  fun `в недоверенном проекте писать и запускать нельзя`() {
    assertFalse(McpAccess.allowed(McpProtocol.Risk.WRITE, trusted = false))
    assertFalse(McpAccess.allowed(McpProtocol.Risk.EXECUTE, trusted = false))
  }

  @Test
  fun `в доверенном проекте разрешено всё`() {
    for (risk in McpProtocol.Risk.entries) {
      assertTrue(McpAccess.allowed(risk, trusted = true), "класс $risk")
    }
  }

  @Test
  fun `запуск агента и запись решения объявлены опасными`() {
    assertEquals(McpProtocol.Risk.EXECUTE, McpProtocol.riskOf(McpProtocol.TOOL_RUN))
    assertEquals(McpProtocol.Risk.WRITE, McpProtocol.riskOf(McpProtocol.TOOL_DECISIONS_RECORD))
  }

  @Test
  fun `у каждого объявленного инструмента есть класс опасности`() {
    // Неизвестное имя падает в EXECUTE, поэтому забытый инструмент просто перестал бы работать в
    // недоверенном проекте молча. Тест требует осознанного решения для каждого имени из списка.
    val known = McpProtocol.TOOLS.map { it.name }.toSet()
    val readable = known.filter { McpProtocol.riskOf(it) == McpProtocol.Risk.READ }
    assertEquals(
      known.size - 2,
      readable.size,
      "читающих инструментов должно быть на два меньше общего числа: запись решения и запуск агента",
    )
  }

  @Test
  fun `неизвестный инструмент считается опасным`() {
    assertEquals(McpProtocol.Risk.EXECUTE, McpProtocol.riskOf("vibe_something_new"))
  }

  @Test
  fun `отказ называет причину и способ её снять`() {
    // Отказ без причины агент читает как поломку инструмента и пробует снова; названная причина
    // превращает его в решение человека, которое агент передаст словами.
    assertTrue(McpAccess.refusal.contains("доверенным"), McpAccess.refusal)
    assertTrue(McpAccess.refusal.contains("Trust Project"), McpAccess.refusal)
  }
}
