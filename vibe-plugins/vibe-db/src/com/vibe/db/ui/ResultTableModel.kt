// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db.ui

import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.db.ResultTable
import com.vibe.db.RowEdit
import javax.swing.table.AbstractTableModel

/**
 * Модель таблицы результата с буфером правок.
 *
 * Правка не уходит в базу по нажатию Enter в ячейке: она копится здесь, пока человек не нажмёт
 * «Применить» и не увидит готовые `UPDATE`. Причина в цене ошибки — случайно набранный символ в
 * ячейке боевой таблицы должен быть отменяемым, а выполненный `UPDATE` отменить нечем.
 *
 * Ячейка редактируема, только если правка доказуемо адресуема: [target] с первичным ключом в самой
 * выборке. Всё остальное — просмотр, как и было.
 */
class ResultTableModel(
  val table: ResultTable.Table,
  private val target: RowEdit.Target?,
) : AbstractTableModel() {
  /** Правки: (строка, столбец) → новое значение; null означает «очистить в NULL». */
  private val edits = HashMap<Pair<Int, Int>, String?>()

  override fun getRowCount(): Int = table.rows.size
  override fun getColumnCount(): Int = table.columns.size
  override fun getColumnName(column: Int): String = table.columns[column].label

  override fun isCellEditable(row: Int, column: Int): Boolean {
    val cells = table.rows.getOrNull(row) ?: return false
    // Двоичное не правим: в ячейке видно «5 байт», а набрать байты текстом нельзя.
    if (cells.getOrNull(column) is ResultTable.Cell.Binary) return false
    return RowEdit.update(target ?: return false, table.columns, cells, column, "") is RowEdit.Statement.Sql
  }

  /** Правки этой ячейки ещё не в базе — интерфейс подсвечивает её, чтобы это было видно. */
  fun isEdited(row: Int, column: Int): Boolean = edits.containsKey(row to column)

  fun hasEdits(): Boolean = edits.isNotEmpty()

  fun discardEdits() {
    val touched = edits.keys.toList()
    edits.clear()
    touched.forEach { (row, column) -> fireTableCellUpdated(row, column) }
  }

  /**
   * Готовые операторы для всех накопленных правок.
   *
   * Порядок — по строкам и столбцам, а не по времени правок: человек читает список перед
   * выполнением, и порядок «как в таблице» читается, а «как нажимал» — нет.
   */
  fun pendingStatements(): List<String> = edits.entries
    .sortedWith(compareBy({ it.key.first }, { it.key.second }))
    .mapNotNull { (cell, value) ->
      val row = table.rows.getOrNull(cell.first) ?: return@mapNotNull null
      (RowEdit.update(target ?: return@mapNotNull null, table.columns, row, cell.second, value)
        as? RowEdit.Statement.Sql)?.text
    }

  override fun getValueAt(row: Int, column: Int): String {
    val key = row to column
    if (edits.containsKey(key)) return edits[key] ?: t("db.null")
    val cell = table.rows.getOrNull(row)?.getOrNull(column) ?: return ""
    return ResultTable.render(cell, t("db.null")) { bytes -> t("db.binary", "bytes" to bytes) }
  }

  /**
   * Введённое значение попадает в буфер, а не в базу.
   *
   * Текст, совпавший с показанным обозначением `NULL`, означает очистку: иначе поле нельзя было бы
   * обнулить руками, а отдельная кнопка ради одного случая — лишняя сущность.
   */
  override fun setValueAt(value: Any?, row: Int, column: Int) {
    val text = value?.toString()
    val newValue = if (text == null || text == t("db.null")) null else text
    val cell = table.rows.getOrNull(row)?.getOrNull(column)
    if (!RowEdit.changed(cell, newValue)) { edits.remove(row to column); fireTableCellUpdated(row, column); return }
    edits[row to column] = newValue
    fireTableCellUpdated(row, column)
  }
}
