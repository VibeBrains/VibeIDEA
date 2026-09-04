// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RowExportTest {
  private val columns = listOf(
    ResultTable.Column("id", "int"),
    ResultTable.Column("name", "text"),
    ResultTable.Column("note", "text"),
    ResultTable.Column("avatar", "blob"),
  )
  private val row = listOf(
    ResultTable.Cell.Text("007"),
    ResultTable.Cell.Text("О'Коннор"),
    ResultTable.Cell.Null,
    ResultTable.Cell.Binary(5),
  )

  @Test
  fun `в JSON NULL остаётся null, а идентификатор с нулями — строкой`() {
    val json = RowExport.toJson(columns, row) { "[$it]" }
    assertTrue(json.contains("\"note\": null"), json)
    // 007 — идентификатор, а не семёрка: превратив его в число, мы потеряем ведущие нули.
    assertTrue(json.contains("\"id\": \"007\""), json)
    assertTrue(json.contains("\"name\": \"О'Коннор\""), json)
    assertTrue(json.contains("\"avatar\": \"[5]\""), json)
  }

  @Test
  fun `число остаётся числом`() {
    val json = RowExport.toJson(listOf(ResultTable.Column("n", "int")), listOf(ResultTable.Cell.Text("42"))) { "" }
    assertTrue(json.contains("\"n\": 42"), json)
    assertTrue(RowExport.looksNumeric("-3.5"))
    assertFalse(RowExport.looksNumeric("007"))
    assertFalse(RowExport.looksNumeric("1,5"))
    assertFalse(RowExport.looksNumeric(""))
  }

  @Test
  fun `INSERT экранирует апостроф, иначе запрос сломается на чужой базе`() {
    val insert = RowExport.toInsert("users", "public", columns, row)
    assertTrue(insert.startsWith("INSERT INTO \"public\".\"users\" (\"id\", \"name\", \"note\", \"avatar\")"), insert)
    assertTrue(insert.contains("'О''Коннор'"), insert)
    assertTrue(insert.contains("NULL"), "NULL и двоичное переносятся как NULL")
  }

  @Test
  fun `WHERE сравнивает NULL через IS NULL`() {
    // «= NULL» не истинно никогда: запрос молча вернул бы пусто, а человек решил бы, что строка исчезла.
    val where = RowExport.toWhere(columns, row, listOf("id", "note"))
    assertEquals("WHERE \"id\" = '007' AND \"note\" IS NULL", where)
    assertEquals("", RowExport.toWhere(columns, row, emptyList()), "без ключей условия нет")
  }

  @Test
  fun `перевод строки и кавычка в JSON экранируются`() {
    val json = RowExport.toJson(
      listOf(ResultTable.Column("t", "text")),
      listOf(ResultTable.Cell.Text("строка\nс \"кавычкой\"")),
    ) { "" }
    assertTrue(json.contains("\\n"), json)
    assertTrue(json.contains("\\\""), json)
  }
}
