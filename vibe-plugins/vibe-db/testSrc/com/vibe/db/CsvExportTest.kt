// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CsvExportTest {
  private fun table(vararg rows: List<ResultTable.Cell>) = ResultTable.Table(
    columns = listOf(ResultTable.Column("id", "int"), ResultTable.Column("name", "text")),
    rows = rows.toList(),
    truncated = false,
  )

  @Test
  fun `запятая, кавычка и перевод строки внутри значения не ломают файл`() {
    // Именно здесь CSV ломается так, что это замечают уже в чужой таблице.
    val csv = CsvExport.render(table(listOf(
      ResultTable.Cell.Text("1"),
      ResultTable.Cell.Text("Иванов, Иван \"Ваня\"\nвторая строка"),
    )))
    assertTrue(csv.contains("\"Иванов, Иван \"\"Ваня\"\"\nвторая строка\""), csv)
    assertEquals("id,name\r\n", csv.lineSequence().first() + "\r\n")
  }

  @Test
  fun `разделитель выбирается — точка с запятой для Excel в русской локали`() {
    val csv = CsvExport.render(table(listOf(ResultTable.Cell.Text("1"), ResultTable.Cell.Text("а;б"))),
                               separator = CsvExport.Separator.SEMICOLON)
    assertTrue(csv.contains("id;name"), csv)
    assertTrue(csv.contains("\"а;б\""), "значение с новым разделителем берётся в кавычки")
  }

  @Test
  fun `NULL и двоичное показываются так, как решил вызывающий`() {
    // В CSV нет способа отличить «пусто» от «нет значения»; притворяться, что есть, хуже.
    val csv = CsvExport.render(
      table(listOf(ResultTable.Cell.Null, ResultTable.Cell.Binary(7))),
      nullText = "", binaryText = { "<$it Б>" },
    )
    assertTrue(csv.endsWith(",<7 Б>\r\n"), csv)
  }

  @Test
  fun `имя файла собирается из запроса и не содержит мусора`() {
    assertEquals("SELECT-FROM-users.csv", CsvExport.fileName("SELECT * FROM users\nWHERE id = 1"))
    // Буквы ЛЮБОГО языка остаются: правило про \p{L}, а не про две азбуки, — иначе имя таблицы
    // на греческом или иврите превращалось бы в череду дефисов.
    assertEquals("SELECT-из-таблицы.csv", CsvExport.fileName("SELECT из таблицы"))
    assertEquals("result.csv", CsvExport.fileName("   \n"))
    assertTrue(CsvExport.fileName("a".repeat(100)).length <= 44)
  }
}
