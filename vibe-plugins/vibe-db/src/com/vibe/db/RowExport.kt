// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

/**
 * Строка таблицы как текст, который можно куда-то положить.
 *
 * Три вещи, за которыми в клиент базы возвращаются каждый день: скопировать строку в тикет (JSON),
 * повторить её на другом стенде (INSERT) и найти её снова (WHERE по ключу). Без них человек
 * выделяет ячейки по одной и склеивает руками — и ошибается в кавычках.
 *
 * Чистая: строка внутрь, текст наружу.
 */
object RowExport {
  /**
   * JSON одной строки.
   *
   * `NULL` становится `null`, а не строкой «NULL»: это JSON, и в нём разница между ними
   * выражается. Числа остаются числами, если исходное значение выглядит числом целиком, — иначе
   * идентификатор `007` превратился бы в `7`.
   */
  fun toJson(columns: List<ResultTable.Column>, row: List<ResultTable.Cell>, binaryText: (Int) -> String): String {
    val fields = columns.mapIndexed { index, column ->
      val value = when (val cell = row.getOrNull(index)) {
        null, is ResultTable.Cell.Null -> "null"
        is ResultTable.Cell.Binary -> quote(binaryText(cell.bytes))
        is ResultTable.Cell.Text -> if (looksNumeric(cell.value)) cell.value else quote(cell.value)
      }
      "  " + quote(column.label) + ": " + value
    }
    return "{\n" + fields.joinToString(",\n") + "\n}"
  }

  /**
   * `INSERT` для переноса строки на другой стенд.
   *
   * Имена в двойных кавычках, значения — в одинарных с удвоением: строка с апострофом в имени
   * («О'Коннор») иначе собрала бы синтаксически битый запрос, и увидели бы это уже на чужой базе.
   */
  fun toInsert(table: String, schema: String?, columns: List<ResultTable.Column>, row: List<ResultTable.Cell>): String {
    val name = if (schema.isNullOrBlank()) QueryLimit.quote(table) else QueryLimit.quote(schema) + "." + QueryLimit.quote(table)
    val names = columns.joinToString(", ") { QueryLimit.quote(it.label) }
    val values = columns.indices.joinToString(", ") { index -> sqlLiteral(row.getOrNull(index)) }
    return "INSERT INTO $name ($names)\nVALUES ($values);"
  }

  /**
   * `WHERE` по значениям указанных столбцов — чтобы найти эту же строку снова.
   *
   * `NULL` сравнивается через `IS NULL`: `= NULL` не истинно никогда, и запрос молча вернул бы
   * пусто, а человек решил бы, что строка исчезла.
   */
  fun toWhere(columns: List<ResultTable.Column>, row: List<ResultTable.Cell>, keyColumns: List<String>): String {
    val parts = columns.mapIndexedNotNull { index, column ->
      if (column.label !in keyColumns) return@mapIndexedNotNull null
      val cell = row.getOrNull(index)
      if (cell == null || cell is ResultTable.Cell.Null) QueryLimit.quote(column.label) + " IS NULL"
      else QueryLimit.quote(column.label) + " = " + sqlLiteral(cell)
    }
    return if (parts.isEmpty()) "" else "WHERE " + parts.joinToString(" AND ")
  }

  private fun sqlLiteral(cell: ResultTable.Cell?): String = when (cell) {
    null, is ResultTable.Cell.Null -> "NULL"
    // Двоичное не переносим: показать размер честно, а выдумать байты нельзя.
    is ResultTable.Cell.Binary -> "NULL"
    is ResultTable.Cell.Text -> if (looksNumeric(cell.value)) cell.value else "'" + cell.value.replace("'", "''") + "'"
  }

  /** Число целиком, без ведущих нулей: `007` — это идентификатор, а не семёрка. */
  fun looksNumeric(value: String): Boolean =
    value.isNotEmpty() && Regex("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$").matches(value)

  private fun quote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
