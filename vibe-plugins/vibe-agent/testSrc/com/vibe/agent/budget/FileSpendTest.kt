// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.budget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileSpendTest {
  @Test
  fun `доля делится по размеру, а сумма долей равна целому`() {
    val split = FileSpend.attribute(1000, listOf(
      FileSpend.Attachment("большой.kt", 750),
      FileSpend.Attachment("маленький.kt", 250),
    ))
    assertEquals(750, split.getValue("большой.kt"))
    assertEquals(250, split.getValue("маленький.kt"))
    assertEquals(1000, split.values.sum())
  }

  @Test
  fun `остаток от деления не теряется, а уходит крупнейшему`() {
    // Иначе отчёт молча показывал бы меньше, чем потрачено, и заметить это было бы нечем.
    val split = FileSpend.attribute(100, listOf(
      FileSpend.Attachment("a", 3), FileSpend.Attachment("b", 3), FileSpend.Attachment("c", 4),
    ))
    assertEquals(100, split.values.sum())
    assertTrue(split.getValue("c") >= split.getValue("a"))
  }

  @Test
  fun `нечего делить — нечего и приписывать`() {
    assertTrue(FileSpend.attribute(0, listOf(FileSpend.Attachment("a", 10))).isEmpty())
    assertTrue(FileSpend.attribute(100, emptyList()).isEmpty())
    assertTrue(FileSpend.attribute(100, listOf(FileSpend.Attachment("пусто", 0))).isEmpty())
  }

  @Test
  fun `один и тот же файл в ходе дважды складывается, а не перетирается`() {
    val split = FileSpend.attribute(100, listOf(
      FileSpend.Attachment("файл", 30), FileSpend.Attachment("файл", 70),
    ))
    assertEquals(100, split.getValue("файл"))
  }

  @Test
  fun `верхушка отчёта — самые дорогие файлы, с числом ходов`() {
    val entries = listOf(
      entry(mapOf("дорогой.kt" to 500L, "дешёвый.kt" to 10L)),
      entry(mapOf("дорогой.kt" to 300L)),
    )
    val top = FileSpend.top(entries)
    assertEquals("дорогой.kt", top.first().path)
    assertEquals(800, top.first().tokens)
    assertEquals(2, top.first().turns, "число ходов отвечает на «это один раз или каждый ход»")
    assertEquals(2, top.size)
  }

  @Test
  fun `предел выдачи соблюдается`() {
    val entries = listOf(entry((1..30).associate { "файл$it.kt" to it.toLong() }))
    assertEquals(10, FileSpend.top(entries).size)
    assertEquals(3, FileSpend.top(entries, limit = 3).size)
  }

  @Test
  fun `старые записи без разбивки по файлам отчёт не ломают`() {
    assertTrue(FileSpend.top(listOf(entry(emptyMap()))).isEmpty())
  }

  private fun entry(files: Map<String, Long>) =
    SpendLedger.Entry(atMs = 0, role = null, target = "модель", tokens = files.values.sum(), files = files)
}
