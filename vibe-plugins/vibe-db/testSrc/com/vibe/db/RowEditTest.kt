// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Правка данных: без доказуемого адреса строки запрос не собирается вовсе. */
class RowEditTest {
  private val columns = listOf(
    ResultTable.Column("id", "INTEGER"),
    ResultTable.Column("name", "TEXT"),
  )
  private val row = listOf(ResultTable.Cell.Text("7"), ResultTable.Cell.Text("О'Коннор"))

  private fun target(keys: List<String> = listOf("id"), table: String? = "users", readOnly: Boolean = false) =
    RowEdit.Target(schema = null, table = table, keyColumns = keys, readOnly = readOnly)

  private fun sql(statement: RowEdit.Statement): String = (statement as RowEdit.Statement.Sql).text

  private fun refusal(statement: RowEdit.Statement): RowEdit.Refusal = (statement as RowEdit.Statement.Refused).refusal

  @Test
  fun `правка собирает UPDATE по ключу`() {
    assertEquals(
      "UPDATE \"users\" SET \"name\" = 'Пётр' WHERE \"id\" = 7",
      sql(RowEdit.update(target(), columns, row, columnIndex = 1, newValue = "Пётр")),
    )
  }

  @Test
  fun `апостроф в новом значении удваивается`() {
    assertTrue(sql(RowEdit.update(target(), columns, row, 1, "О'Коннор")).contains("'О''Коннор'"))
  }

  @Test
  fun `очистка ячейки пишет NULL, а пустая строка остаётся строкой`() {
    assertTrue(sql(RowEdit.update(target(), columns, row, 1, null)).contains("SET \"name\" = NULL"))
    assertTrue(sql(RowEdit.update(target(), columns, row, 1, "")).contains("SET \"name\" = ''"))
  }

  @Test
  fun `без ключа в выборке правка отклоняется`() {
    assertEquals(RowEdit.Refusal.NO_KEY, refusal(RowEdit.update(target(keys = listOf("uuid")), columns, row, 1, "x")))
  }

  @Test
  fun `результат не из одной таблицы не правится`() {
    assertEquals(RowEdit.Refusal.NOT_SINGLE_TABLE, refusal(RowEdit.update(target(table = null), columns, row, 1, "x")))
  }

  @Test
  fun `пустой ключ не даёт адреса строки`() {
    val nullKey = listOf(ResultTable.Cell.Null, ResultTable.Cell.Text("Иван"))
    assertEquals(RowEdit.Refusal.KEY_IS_NULL, refusal(RowEdit.update(target(), columns, nullKey, 1, "x")))
  }

  @Test
  fun `сам ключ не правится`() {
    assertEquals(RowEdit.Refusal.KEY_COLUMN, refusal(RowEdit.update(target(), columns, row, 0, "8")))
  }

  @Test
  fun `подключение только для чтения не правится`() {
    assertEquals(RowEdit.Refusal.READ_ONLY, refusal(RowEdit.update(target(readOnly = true), columns, row, 1, "x")))
  }

  @Test
  fun `составной ключ попадает в WHERE целиком`() {
    val cols = listOf(
      ResultTable.Column("tenant", "TEXT"),
      ResultTable.Column("id", "INTEGER"),
      ResultTable.Column("name", "TEXT"),
    )
    val values = listOf(ResultTable.Cell.Text("acme"), ResultTable.Cell.Text("7"), ResultTable.Cell.Text("Иван"))
    val text = sql(RowEdit.update(target(keys = listOf("tenant", "id")), cols, values, 2, "Пётр"))
    assertTrue(text.endsWith("WHERE \"tenant\" = 'acme' AND \"id\" = 7"), text)
  }

  @Test
  fun `правка без изменения значения не считается правкой`() {
    assertFalse(RowEdit.changed(ResultTable.Cell.Text("Иван"), "Иван"))
    assertTrue(RowEdit.changed(ResultTable.Cell.Text("Иван"), "Пётр"))
    assertTrue(RowEdit.changed(ResultTable.Cell.Text("Иван"), null))
    assertFalse(RowEdit.changed(ResultTable.Cell.Null, null))
  }
}
