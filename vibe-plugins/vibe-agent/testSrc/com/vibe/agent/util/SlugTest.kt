// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.util

import kotlin.test.Test
import kotlin.test.assertEquals

/** Имя файла из заголовка: одно место правил на все корпуса, и никакой молчаливой перезаписи. */
class SlugTest {
  @Test
  fun `кириллица транслитерируется, а не выбрасывается`() {
    assertEquals("dogovor-s-podryadchikom", Slug.of("Договор с подрядчиком"))
  }

  @Test
  fun `из мусора получается осмысленное имя, а не пустое`() {
    assertEquals("note", Slug.of("！？"))
    assertEquals("decision", Slug.of("！？", fallback = "decision"))
  }

  @Test
  fun `занятое имя получает суффикс, а не затирает чужой файл`() {
    assertEquals("otchet.md", Slug.unique("otchet.md", emptyList()))
    assertEquals("otchet-2.md", Slug.unique("otchet.md", listOf("otchet.md")))
    assertEquals("otchet-3.md", Slug.unique("otchet.md", listOf("otchet.md", "otchet-2.md")))
  }

  @Test
  fun `имя без расширения тоже не затирается`() {
    assertEquals("plan-2", Slug.unique("plan", listOf("plan")))
  }
}
