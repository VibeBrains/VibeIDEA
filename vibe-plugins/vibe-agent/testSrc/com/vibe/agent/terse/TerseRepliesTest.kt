// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.terse

import com.vibe.agent.terse.TerseReplies.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Terse replies read the shipped file of the shared set, so a section renamed in the set fails here and not in a chat
 */
class TerseRepliesTest {
  private val file: String by lazy {
    javaClass.getResource("/vibeDefaults/${TerseReplies.FILE}")?.readText()
      ?: error("нет ${TerseReplies.FILE} в classpath — указатель набора не поднят?")
  }

  private val levels = Level.entries - Level.OFF

  @Test
  fun `every level has its section, and off sends nothing`() {
    for (level in levels) {
      val text = TerseReplies.instruction(file, level)
      assertTrue(text.contains("## Level: ${level.id}"), level.id)
      for (other in levels - level) assertFalse(text.contains("## Level: ${other.id}"), "${level.id} несёт ${other.id}")
    }
    assertEquals("", TerseReplies.instruction(file, Level.OFF))
  }

  @Test
  fun `the common sections go with every level, in file order, and the license stays out`() {
    val text = TerseReplies.instruction(file, Level.FULL)
    val order = listOf("## Reply style: terse", "## Rules", "## Level: full", "## Clear parts", "## Boundaries")
    val positions = order.map { text.indexOf(it) }
    assertTrue(positions.none { it < 0 }, "нет раздела: ${order.filterIndexed { i, _ -> positions[i] < 0 }}")
    assertEquals(positions.sorted(), positions, "разделы не в порядке файла")
    assertFalse(text.contains("## Off"))
    assertFalse(text.contains("## Short"), "сжатая форма ушла вместе с полной")
    assertFalse(text.contains("Permission is hereby granted"), "лицензия ушла модели")
  }

  @Test
  fun `an unknown or empty level is the default, not off`() {
    assertEquals(Level.FULL, Level.of(null))
    assertEquals(Level.FULL, Level.of("fulll"))
    assertEquals(Level.ULTRA, Level.of(" ULTRA "))
    assertEquals(Level.OFF, Level.of("off"))
  }

  @Test
  fun `an agent gets the style once and again only when the level changes`() {
    assertEquals(TerseReplies.instruction(file, Level.FULL), TerseReplies.forAgent(file, null, Level.FULL))
    assertNull(TerseReplies.forAgent(file, Level.FULL, Level.FULL))
    assertEquals(TerseReplies.instruction(file, Level.LITE), TerseReplies.forAgent(file, Level.FULL, Level.LITE))
    // Switched off: an agent that was given the style is told so, one that was never given it hears nothing
    assertEquals(TerseReplies.offNotice(file), TerseReplies.forAgent(file, Level.FULL, Level.OFF))
    assertTrue(TerseReplies.offNotice(file).startsWith("## Off"))
    assertNull(TerseReplies.forAgent(file, null, Level.OFF))
  }

  @Test
  fun `a file without the level section gives no instruction rather than half of one`() {
    val text = "## Rules\n\nBe short.\n\n## Level: lite\n\nLite."
    assertEquals("", TerseReplies.instruction(text, Level.FULL))
    assertEquals("## Rules\n\nBe short.\n\n## Level: lite\n\nLite.", TerseReplies.instruction(text, Level.LITE))
  }

  @Test
  fun `the short form is the short section and the level, without the common sections`() {
    for (level in levels) {
      val text = TerseReplies.instruction(file, level, short = true)
      assertTrue(text.contains("## Short"), level.id)
      assertTrue(text.contains("## Level: ${level.id}"), level.id)
      for (common in listOf("## Rules", "## Boundaries", "## Clear parts", "## Off")) assertFalse(text.contains(common), common)
    }
    assertEquals("", TerseReplies.instruction(file, Level.OFF, short = true))
  }

  @Test
  fun `a file without the short section gives the full text to a local model`() {
    val text = "## Rules\n\nBe short.\n\n## Level: full\n\nFull."
    assertEquals(TerseReplies.instruction(text, Level.FULL), TerseReplies.instruction(text, Level.FULL, short = true))
  }
}
