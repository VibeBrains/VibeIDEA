// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

/**
 * Таблица результата — то, ради чего инструмент открывают.
 *
 * Чистая модель отдельно от JDBC: превращение значения в текст решает, увидит ли человек разницу
 * между `NULL` и пустой строкой, между числом и строкой, похожей на число. Это решения, а не
 * отрисовка, и проверять их надо тестом, а не глазами по живой базе.
 */
object ResultTable {
  /** Значение ячейки: `null` базы — отдельный случай, а не пустая строка. */
  sealed interface Cell {
    data object Null : Cell
    data class Text(val value: String) : Cell
    /** Двоичное: показываем размер, а не байты — иначе таблица превращается в кашу. */
    data class Binary(val bytes: Int) : Cell
  }

  data class Column(val label: String, val typeName: String)

  data class Table(val columns: List<Column>, val rows: List<List<Cell>>, val truncated: Boolean) {
    val rowCount: Int get() = rows.size
  }

  /**
   * Как показать значение в ячейке.
   *
   * `NULL` печатается словом, а не пустотой: в базе это разные вещи, и человек, чинящий данные,
   * должен видеть, какая именно перед ним.
   */
  fun render(cell: Cell, nullText: String, binaryText: (Int) -> String): String = when (cell) {
    is Cell.Null -> nullText
    is Cell.Text -> cell.value
    is Cell.Binary -> binaryText(cell.bytes)
  }

  /**
   * Заголовки столбцов: одинаковые имена разводятся суффиксом.
   *
   * `SELECT a.id, b.id FROM …` даёт два столбца `id`, и без развода второй молча заслоняет первый —
   * человек смотрит на данные и не понимает, почему они одинаковые.
   */
  fun uniqueLabels(labels: List<String>): List<String> {
    val seen = HashMap<String, Int>()
    return labels.map { label ->
      val count = seen.merge(label, 1, Int::plus)!!
      if (count == 1) label else "$label ($count)"
    }
  }

  /** Ширина столбца в символах по содержимому — чтобы таблица открывалась уже читаемой. */
  fun preferredWidth(column: Column, rows: List<List<Cell>>, index: Int, nullText: String, cap: Int = 60): Int {
    val content = rows.asSequence()
      .mapNotNull { it.getOrNull(index) }
      .map { render(it, nullText) { bytes -> "[$bytes]" }.length }
      .maxOrNull() ?: 0
    return minOf(maxOf(column.label.length, content, MIN_WIDTH), cap)
  }

  private const val MIN_WIDTH = 6
}
