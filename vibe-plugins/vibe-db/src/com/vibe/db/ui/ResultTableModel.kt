// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db.ui

import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.db.ResultTable
import javax.swing.table.AbstractTableModel

/**
 * Модель таблицы результата.
 *
 * Только показ: правка данных — отдельная работа с подтверждениями и генерацией UPDATE, и делать
 * её наполовину опаснее, чем не делать вовсе.
 */
class ResultTableModel(private val table: ResultTable.Table) : AbstractTableModel() {
  override fun getRowCount(): Int = table.rows.size
  override fun getColumnCount(): Int = table.columns.size
  override fun getColumnName(column: Int): String = table.columns[column].label
  override fun isCellEditable(row: Int, column: Int): Boolean = false

  override fun getValueAt(row: Int, column: Int): String {
    val cell = table.rows.getOrNull(row)?.getOrNull(column) ?: return ""
    return ResultTable.render(cell, t("db.null")) { bytes -> t("db.binary", "bytes" to bytes) }
  }
}
