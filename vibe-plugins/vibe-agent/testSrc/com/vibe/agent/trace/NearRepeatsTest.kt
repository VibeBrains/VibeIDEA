// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NearRepeatsTest {
  private fun tool(name: String) = TurnTrace.Event(atMs = 0, kind = TurnTrace.Kind.TOOL, name = name)

  @Test
  fun `чтение одного файла разными кусками видно как один вызов`() {
    // Точных дубликатов тут нет, и детектор петель молчит — а круг по файлу есть.
    val events = listOf(
      tool("Read src/Foo.kt (1-50)"),
      tool("Read src/Foo.kt (40-90)"),
      tool("Read src/Foo.kt (80-120)"),
    )
    val near = TurnTrace.nearRepeats(events)
    assertEquals(mapOf("Read src/Foo.kt" to 3), near)
  }

  @Test
  fun `два похожих вызова — ещё не круг`() {
    assertTrue(TurnTrace.nearRepeats(listOf(tool("Read a.kt (1-9)"), tool("Read a.kt (9-20)"))).isEmpty())
  }

  @Test
  fun `разные файлы не сливаются в одну строку`() {
    val events = listOf(tool("Read a.kt (1-5)"), tool("Read b.kt (1-5)"), tool("Read c.kt (1-5)"))
    assertTrue(TurnTrace.nearRepeats(events).isEmpty())
  }

  @Test
  fun `нормализация убирает числа и скобки, но оставляет суть`() {
    assertEquals("Read src/Foo.kt", TurnTrace.normalizeName("Read src/Foo.kt (1-50)"))
    assertEquals("Bash npm run build", TurnTrace.normalizeName("Bash npm run build [12s]"))
    assertEquals("Grep TODO", TurnTrace.normalizeName("Grep TODO"))
  }

  @Test
  fun `не-инструментальные события не считаются`() {
    val events = List(5) { TurnTrace.Event(atMs = 0, kind = TurnTrace.Kind.NOTE, name = "заметка (1)") }
    assertTrue(TurnTrace.nearRepeats(events).isEmpty())
  }
}
