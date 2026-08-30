// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextFilterTest {
  @Test
  fun `progress bars and separators carry no information`() {
    assertTrue(ContextFilter.isNoise(""))
    assertTrue(ContextFilter.isNoise("   "))
    assertTrue(ContextFilter.isNoise("========================"))
    assertTrue(ContextFilter.isNoise("45.2% done"))
    assertTrue(ContextFilter.isNoise("[====>    ]"))
  }

  @Test
  fun `real output is never noise`() {
    assertFalse(ContextFilter.isNoise("FAILED: 3 tests"))
    assertFalse(ContextFilter.isNoise("src/Main.kt:10:5 error"))
  }

  @Test
  fun `raw and off keep the text exactly`() {
    val text = "a\n\n\nb"
    assertEquals(text, ContextFilter.filter(text, ContextFilter.Mode.RAW).text)
    assertEquals(text, ContextFilter.filter(text, ContextFilter.Mode.OFF).text)
  }

  @Test
  fun `auto collapses a run of identical lines but keeps the first repetition`() {
    // «То же самое ещё раз» — информация; четырёхсотый повтор — нет.
    val text = (1..10).joinToString("\n") { "warning: unused import" }
    val result = ContextFilter.filter(text, ContextFilter.Mode.AUTO)
    assertEquals(2, result.text.lines().count { it == "warning: unused import" })
    assertTrue(result.text.contains("×"))
    assertTrue(result.removedLines >= 7)
  }

  @Test
  fun `auto keeps the error that mattered`() {
    val text = "building\n" + "=".repeat(20) + "\n" + (1..50).joinToString("\n") { "12% done" } + "\nFAILED: NullPointerException"
    val result = ContextFilter.filter(text, ContextFilter.Mode.AUTO)
    assertTrue(result.text.contains("FAILED: NullPointerException"))
    assertFalse(result.text.contains("12% done"))
  }

  @Test
  fun `aggregate keeps the shape, most frequent first`() {
    val text = "b\na\na\na\nc"
    val result = ContextFilter.filter(text, ContextFilter.Mode.AGGREGATE)
    assertTrue(result.text.startsWith("a"))
    assertTrue(result.text.contains("×3"))
  }

  @Test
  fun `the mode name maps to a mode, and anything unknown is auto`() {
    assertEquals(ContextFilter.Mode.RAW, ContextFilter.modeOf("raw"))
    assertEquals(ContextFilter.Mode.AGGREGATE, ContextFilter.modeOf("AGGREGATE"))
    assertEquals(ContextFilter.Mode.OFF, ContextFilter.modeOf("off"))
    assertEquals(ContextFilter.Mode.AUTO, ContextFilter.modeOf("что-то своё"))
    assertEquals(ContextFilter.Mode.AUTO, ContextFilter.modeOf(null))
  }

  @Test
  fun `filtering reports how much it removed`() {
    // Молчаливая чистка неотличима от пустого вывода инструмента.
    val result = ContextFilter.filter("a\n\n\n\nb", ContextFilter.Mode.AUTO)
    assertEquals(3, result.removedLines)
  }
}
