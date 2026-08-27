// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SkillsRegistryTest {
  @Test
  fun `frontmatter description wins`() {
    val text = """
      ---
      name: watch
      description: "Смотрит любое видео по ссылке"
      ---
      # Заголовок
      Тело.
    """.trimIndent()
    assertEquals("Смотрит любое видео по ссылке", SkillsRegistry.parseDescription(text))
  }

  @Test
  fun `first plain line when no frontmatter, headings skipped`() {
    val text = "# Скилл\n\nПервая строка описания.\nВторая."
    assertEquals("Первая строка описания.", SkillsRegistry.parseDescription(text))
  }

  @Test
  fun `body after frontmatter without description`() {
    val text = "---\nname: x\n---\n\nОписание из тела."
    assertEquals("Описание из тела.", SkillsRegistry.parseDescription(text))
  }

  @Test
  fun `long description is truncated`() {
    val long = "а".repeat(200)
    val parsed = SkillsRegistry.parseDescription(long)
    assertEquals(SkillsRegistry.DESCRIPTION_LIMIT + 1, parsed.length)
    assertEquals('…', parsed.last())
  }

  @Test
  fun `empty file gives empty description`() {
    assertEquals("", SkillsRegistry.parseDescription(""))
  }
}
