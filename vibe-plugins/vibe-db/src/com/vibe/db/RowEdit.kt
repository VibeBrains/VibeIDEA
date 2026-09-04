// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

/**
 * Правка ячейки результата — как готовый `UPDATE`.
 *
 * Правка данных опаснее всего остального в клиенте базы: `UPDATE` без точного `WHERE` меняет не
 * строку, а таблицу, и узнают об этом позже всех. Поэтому здесь нет «попробуем угадать строку»:
 * либо правка адресуема первичным ключом, который есть в самой выборке, либо мы отказываем и
 * называем причину кодом. Отказ — нормальный результат, а не ошибка.
 *
 * Чистая: описание правки внутрь, текст запроса наружу. Выполняет его [JdbcSession].
 */
object RowEdit {
  /** Почему править нельзя — кодом; фразу собирает интерфейс. */
  enum class Refusal {
    /** В выборке нет первичного ключа таблицы — адресовать строку нечем. */
    NO_KEY,
    /** Результат не из одной таблицы (join, вычисляемые столбцы) — неизвестно, что менять. */
    NOT_SINGLE_TABLE,
    /** Значение ключа в этой строке пусто: `WHERE key = NULL` не найдёт ничего. */
    KEY_IS_NULL,
    /** Подключение объявлено «только чтение». */
    READ_ONLY,
    /** Правится столбец, который сам входит в ключ, — это не правка, а перенос строки. */
    KEY_COLUMN,
  }

  /** Таблица, к которой относится результат, и её ключ. Null-таблица = результат не из одной таблицы. */
  data class Target(val schema: String?, val table: String?, val keyColumns: List<String>, val readOnly: Boolean)

  sealed interface Statement {
    data class Sql(val text: String) : Statement
    data class Refused(val refusal: Refusal, val detail: String) : Statement
  }

  /**
   * `UPDATE` для одной ячейки.
   *
   * Новое значение приходит текстом (человек набрал его в таблице): пустая строка остаётся пустой
   * строкой, а `NULL` выражается отдельным флагом — иначе поле нельзя было бы очистить, не потеряв
   * возможность записать в него пустую строку.
   */
  fun update(
    target: Target,
    columns: List<ResultTable.Column>,
    row: List<ResultTable.Cell>,
    columnIndex: Int,
    newValue: String?,
  ): Statement {
    if (target.readOnly) return Statement.Refused(Refusal.READ_ONLY, target.table ?: "")
    val table = target.table?.takeIf { it.isNotBlank() } ?: return Statement.Refused(Refusal.NOT_SINGLE_TABLE, "")
    val column = columns.getOrNull(columnIndex) ?: return Statement.Refused(Refusal.NOT_SINGLE_TABLE, "")
    val keys = target.keyColumns.filter { key -> columns.any { it.label.equals(key, ignoreCase = true) } }
    if (keys.isEmpty()) return Statement.Refused(Refusal.NO_KEY, table)
    if (keys.any { it.equals(column.label, ignoreCase = true) }) return Statement.Refused(Refusal.KEY_COLUMN, column.label)

    val where = ArrayList<String>(keys.size)
    for (key in keys) {
      val index = columns.indexOfFirst { it.label.equals(key, ignoreCase = true) }
      val cell = row.getOrNull(index)
      if (cell == null || cell is ResultTable.Cell.Null) return Statement.Refused(Refusal.KEY_IS_NULL, key)
      where.add(QueryLimit.quote(columns[index].label) + " = " + SqlLiteral.of(cell))
    }
    val name = if (target.schema.isNullOrBlank()) QueryLimit.quote(table)
    else QueryLimit.quote(target.schema) + "." + QueryLimit.quote(table)
    val value = if (newValue == null) "NULL" else SqlLiteral.ofText(newValue)
    return Statement.Sql(
      "UPDATE $name SET " + QueryLimit.quote(column.label) + " = " + value + " WHERE " + where.joinToString(" AND ")
    )
  }

  /** Изменилось ли значение на самом деле: правка, ничего не меняющая, не должна ехать в базу. */
  fun changed(cell: ResultTable.Cell?, newValue: String?): Boolean = when {
    newValue == null -> cell != null && cell !is ResultTable.Cell.Null
    cell is ResultTable.Cell.Text -> cell.value != newValue
    else -> true
  }
}
