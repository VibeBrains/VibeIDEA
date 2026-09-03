// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VibeAboutInfoTest {
  private val labels = object : VibeAboutInfo.Labels {
    override val version = "Версия"
    override val platform = "Платформа"
    override val build = "сборка"
    override val revision = "Коммит"
  }

  @Test
  fun `the product version comes first, the platform second, the commit last`() {
    val lines = VibeAboutInfo.lines("0.3.0", "2026.3", "VI-263.300", "b693c613f6abcdef99", labels)
    assertEquals(listOf("Версия", "Платформа", "Коммит"), lines.map { it.label })
    assertEquals("0.3.0", lines[0].value)
    assertEquals("intellij-community 2026.3, сборка VI-263.300", lines[1].value)
    // Twelve characters: unique in a repository this size, short enough to read out loud.
    assertEquals("b693c613f6ab", lines[2].value)
  }

  @Test
  fun `a build without a recorded revision simply has no commit line`() {
    // A dev run from sources records none; inventing «unknown» would be a value that looks like data.
    assertEquals(2, VibeAboutInfo.lines("0.3.0", "2026.3", "VI-263.SNAPSHOT", null, labels).size)
    assertEquals(2, VibeAboutInfo.lines("0.3.0", "2026.3", "VI-263.SNAPSHOT", "  ", labels).size)
  }

  @Test
  fun `dialog and clipboard are rendered from the same lines`() {
    val lines = VibeAboutInfo.lines("0.3.0", "2026.3", "VI-263.300", "abc123abc123", labels)
    val html = VibeAboutInfo.html(lines)
    val plain = VibeAboutInfo.plain(lines)
    assertTrue(html.contains("<b>Версия:</b> 0.3.0") && html.contains("<br>"))
    assertEquals("Версия: 0.3.0\nПлатформа: intellij-community 2026.3, сборка VI-263.300\nКоммит: abc123abc123", plain)
  }

  @Test
  fun `html escapes what could be read as markup`() {
    val html = VibeAboutInfo.html(listOf(VibeAboutInfo.Line("a<b", "c&d")))
    assertEquals("<b>a&lt;b:</b> c&amp;d", html)
  }
}
