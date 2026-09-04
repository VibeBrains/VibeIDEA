// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

/**
 * Значение ячейки как литерал SQL — один источник правды на весь плагин.
 *
 * Раньше эта логика жила внутри [RowExport]. Пока её звало одно место, это было незаметно;
 * с появлением правки данных мест стало два, и две копии правил цитирования однажды разошлись бы
 * ровно в тот день, когда одна из них выполняется, а не копируется в буфер.
 */
object SqlLiteral {
  /** Число целиком, без ведущих нулей: `007` — это идентификатор, а не семёрка. */
  fun looksNumeric(value: String): Boolean =
    value.isNotEmpty() && Regex("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$").matches(value)

  /**
   * Литерал значения.
   *
   * Апостроф удваивается: строка «О'Коннор» иначе собирает синтаксически битый запрос, и узнают об
   * этом уже на чужой базе. Двоичное не переносится — выдумать байты нельзя, честнее `NULL`.
   */
  fun of(cell: ResultTable.Cell?): String = when (cell) {
    null, is ResultTable.Cell.Null -> "NULL"
    is ResultTable.Cell.Binary -> "NULL"
    is ResultTable.Cell.Text -> ofText(cell.value)
  }

  /** Литерал текста, введённого человеком: пустая строка — это строка, а не `NULL`. */
  fun ofText(value: String): String =
    if (looksNumeric(value)) value else "'" + value.replace("'", "''") + "'"
}
