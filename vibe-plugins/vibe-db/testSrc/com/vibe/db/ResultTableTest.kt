// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResultTableTest {
  @Test
  fun `NULL и пустая строка выглядят по-разному`() {
    // В базе это разные вещи, и человек, чинящий данные, обязан видеть, какая перед ним.
    assertEquals("NULL", ResultTable.render(ResultTable.Cell.Null, "NULL") { "" })
    assertEquals("", ResultTable.render(ResultTable.Cell.Text(""), "NULL") { "" })
  }

  @Test
  fun `двоичное показывается размером, а не байтами`() {
    assertEquals("[12 Б]", ResultTable.render(ResultTable.Cell.Binary(12), "NULL") { "[$it Б]" })
  }

  @Test
  fun `одинаковые имена столбцов разводятся`() {
    // SELECT a.id, b.id — два столбца «id»; без развода второй молча заслоняет первый.
    assertEquals(listOf("id", "id (2)", "name", "id (3)"),
                 ResultTable.uniqueLabels(listOf("id", "id", "name", "id")))
  }

  @Test
  fun `ширина столбца считается по содержимому и ограничена сверху`() {
    val rows = listOf(
      listOf(ResultTable.Cell.Text("короткое")),
      listOf(ResultTable.Cell.Text("з".repeat(200))),
    )
    val column = ResultTable.Column("c", "text")
    assertEquals(60, ResultTable.preferredWidth(column, rows, 0, "NULL"))
    // Пустая колонка не схлопывается в ноль: заголовок всё равно надо прочитать.
    assertTrue(ResultTable.preferredWidth(column, emptyList(), 0, "NULL") >= 6)
  }
}
