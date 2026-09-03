// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

/**
 * Выгрузка результата в CSV.
 *
 * Самая частая просьба к любому инструменту работы с базой — «отдай это в таблицу»: дальше данные
 * уходят в Excel, в тикет, в письмо. Экранирование здесь не деталь, а суть: значение с запятой,
 * кавычкой или переводом строки, записанное как есть, ломает файл так, что это замечают уже
 * в чужой таблице.
 *
 * Чистый: таблица внутрь, текст наружу.
 */
object CsvExport {
  /** Разделитель. Точка с запятой — то, что ждёт Excel в русской локали. */
  enum class Separator(val char: Char) { COMMA(','), SEMICOLON(';'), TAB('\t') }

  /**
   * @param nullText чем показать `NULL`. Пустая строка — законный выбор: в CSV нет способа отличить
   *   «пусто» от «нет значения», и притворяться, что есть, хуже, чем спросить.
   */
  fun render(
    table: ResultTable.Table,
    separator: Separator = Separator.COMMA,
    nullText: String = "",
    binaryText: (Int) -> String = { "[$it]" },
    lineEnding: String = "\r\n",
  ): String {
    val out = StringBuilder()
    out.append(table.columns.joinToString(separator.char.toString()) { escape(it.label, separator) })
    out.append(lineEnding)
    for (row in table.rows) {
      out.append(row.joinToString(separator.char.toString()) { cell ->
        escape(ResultTable.render(cell, nullText, binaryText), separator)
      })
      out.append(lineEnding)
    }
    return out.toString()
  }

  /**
   * Экранирование по RFC 4180: кавычки удваиваются, а значение берётся в кавычки, если содержит
   * разделитель, кавычку или перевод строки.
   */
  fun escape(value: String, separator: Separator): String {
    val needsQuotes = value.any { it == separator.char || it == '"' || it == '\n' || it == '\r' }
    if (!needsQuotes) return value
    return '"' + value.replace("\"", "\"\"") + '"'
  }

  /** Имя файла по умолчанию: без пробелов и без даты, которую всё равно покажет файловая система. */
  fun fileName(query: String): String {
    val base = query.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
      .replace(Regex("[^A-Za-z0-9А-Яа-я_-]+"), "-")
      .trim('-')
      .take(40)
      .ifEmpty { "result" }
    return "$base.csv"
  }
}
