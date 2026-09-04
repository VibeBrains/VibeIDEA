// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

/**
 * Подсказки в консоли SQL по схеме подключения.
 *
 * Смысл не в экономии нажатий, а в том, что имя столбца известно базе и неизвестно человеку:
 * `created_at` или `createdAt`, `user_id` или `userId` — узнаётся это обычно ошибкой выполнения.
 * Поэтому подсказка опирается на прочитанную схему, а не на слова из текста запроса.
 *
 * Чистая: текст до курсора и схема внутрь, список имён наружу. Ни одного похода в базу — схема
 * уже прочитана деревом объектов.
 */
object SqlCompletion {
  /** Что уместно в этой позиции. */
  enum class Kind { TABLE, COLUMN, NONE }

  data class Suggestion(val text: String, val kind: Kind, val detail: String)

  /** Ключевые слова, после которых идёт имя таблицы. */
  private val TABLE_AFTER = setOf("FROM", "JOIN", "UPDATE", "INTO", "TABLE")

  /** Ключевые слова, после которых идут столбцы. */
  private val COLUMN_AFTER = setOf("SELECT", "WHERE", "ON", "SET", "BY", "HAVING", "AND", "OR")

  /** Таблица и её псевдоним в тексте запроса: `users u`, `users AS u`. */
  private val SOURCE = Regex("(?i)\\b(from|join|update|into)\\s+(\"?[\\w$]+\"?(?:\\.\"?[\\w$]+\"?)?)(?:\\s+(?:as\\s+)?(\"?[\\w$]+\"?))?")

  /**
   * Подсказки для позиции курсора.
   *
   * Позиция определяется последним ключевым словом до курсора, а таблицы и псевдонимы читаются из
   * ВСЕГО оператора: `SELECT u.| FROM users u` — курсор стоит раньше, чем объявлен псевдоним, и
   * подсказка по одному лишь тексту слева не знала бы про `u` вообще.
   *
   * Разбирать SQL целиком ради автодополнения не нужно: полноценный парсер придётся чинить на
   * каждом диалекте, а ошибается он ровно там же, где простое правило.
   */
  fun suggest(
    text: String,
    caretOffset: Int,
    schemas: List<DbCatalog.Schema>,
    columnsOf: (DbCatalog.Table) -> List<DbCatalog.Column>,
  ): List<Suggestion> {
    val textBeforeCaret = text.take(caretOffset.coerceIn(0, text.length))
    val prefix = currentWord(textBeforeCaret)
    val qualifier = qualifier(textBeforeCaret)
    if (qualifier != null) {
      val table = resolve(qualifier, text, schemas) ?: return emptyList()
      return columnsOf(table).map { Suggestion(it.name, Kind.COLUMN, it.typeName) }.filterPrefix(prefix)
    }
    return when (kindAt(textBeforeCaret)) {
      Kind.TABLE -> schemas.flatMap { schema ->
        schema.tables.map { Suggestion(it.name, Kind.TABLE, schema.name) }
      }.filterPrefix(prefix)
      Kind.COLUMN -> visibleTables(text, schemas)
        .flatMap { table -> columnsOf(table).map { Suggestion(it.name, Kind.COLUMN, table.name) } }
        .distinctBy { it.text }
        .filterPrefix(prefix)
      Kind.NONE -> emptyList()
    }
  }

  /** Слово, которое человек уже набрал, — по нему отбираются подсказки. */
  fun currentWord(text: String): String = text.takeLastWhile { it.isLetterOrDigit() || it == '_' || it == '$' }

  /**
   * Псевдоним перед точкой: `u.` → `u`.
   *
   * Точка меняет вопрос целиком: спрашивают не «какие вообще столбцы», а «какие столбцы вот у этой
   * таблицы», и подсказать чужие означает подсказать неверно.
   */
  fun qualifier(text: String): String? {
    val head = text.dropLast(currentWord(text).length)
    if (!head.endsWith(".")) return null
    val name = head.dropLast(1).takeLastWhile { it.isLetterOrDigit() || it == '_' || it == '$' || it == '"' }
    return name.trim('"').takeIf { it.isNotEmpty() }
  }

  /** Позиция по последнему ключевому слову до курсора. */
  fun kindAt(text: String): Kind {
    val words = Regex("[A-Za-z_$]+").findAll(text.dropLast(currentWord(text).length)).map { it.value.uppercase() }.toList()
    for (word in words.asReversed()) {
      if (word in TABLE_AFTER) return Kind.TABLE
      if (word in COLUMN_AFTER) return Kind.COLUMN
    }
    return Kind.NONE
  }

  /** Таблицы, упомянутые в запросе, — только их столбцы имеют смысл в `SELECT`/`WHERE`. */
  fun visibleTables(text: String, schemas: List<DbCatalog.Schema>): List<DbCatalog.Table> =
    SOURCE.findAll(text).mapNotNull { match -> find(match.groupValues[2], schemas) }.distinct().toList()

  private fun resolve(qualifier: String, text: String, schemas: List<DbCatalog.Schema>): DbCatalog.Table? {
    for (match in SOURCE.findAll(text)) {
      val alias = match.groupValues[3].trim('"')
      val name = match.groupValues[2]
      if (alias.equals(qualifier, ignoreCase = true) || name.substringAfterLast('.').trim('"').equals(qualifier, ignoreCase = true)) {
        return find(name, schemas)
      }
    }
    return null
  }

  private fun find(name: String, schemas: List<DbCatalog.Schema>): DbCatalog.Table? {
    val clean = name.replace("\"", "")
    val schemaName = clean.substringBeforeLast('.', "")
    val tableName = clean.substringAfterLast('.')
    return schemas.asSequence()
      .filter { schemaName.isEmpty() || it.name.equals(schemaName, ignoreCase = true) }
      .flatMap { it.tables.asSequence() }
      .firstOrNull { it.name.equals(tableName, ignoreCase = true) }
  }

  private fun List<Suggestion>.filterPrefix(prefix: String): List<Suggestion> =
    if (prefix.isEmpty()) this else filter { it.text.startsWith(prefix, ignoreCase = true) }
}
