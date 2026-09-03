// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

/**
 * Ограничение выборки для предпросмотра.
 *
 * Двойной щелчок по таблице не должен вытащить в память миллион строк — но и подменять запрос
 * человека нельзя: если он написал `LIMIT 5`, значит хотел пять. Поэтому предел ставится там, где
 * его нет, а решение принимается по тексту, который мы сами и составили.
 */
object QueryLimit {
  /** Запрос «покажи таблицу» — его составляем мы, поэтому и кавычки ставим сами. */
  fun preview(table: String, schema: String?, rows: Int = DbSettings.DEFAULT_PREVIEW_ROWS): String {
    val name = if (schema.isNullOrBlank()) quote(table) else "${quote(schema)}.${quote(table)}"
    return "SELECT * FROM $name LIMIT $rows"
  }

  /**
   * Имя объекта в кавычках. Двойная кавычка внутри имени удваивается — иначе таблица с кавычкой
   * в имени (редкость, но она бывает) собрала бы синтаксически битый запрос.
   */
  fun quote(name: String): String = '"' + name.replace("\"", "\"\"") + '"'

  private val HAS_LIMIT = Regex("(?is)\\blimit\\s+\\d+|\\bfetch\\s+first\\b|\\btop\\s+\\d+\\b")

  /** Есть ли у запроса свой предел — тогда наш не нужен. */
  fun hasOwnLimit(sql: String): Boolean = HAS_LIMIT.containsMatchIn(sql)
}
