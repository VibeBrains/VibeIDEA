// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ingest

/**
 * Целостность корпуса: файлы, которых нет в индексе.
 *
 * Правило проекта «записи без строки в индексе не существует» держится не само по себе. Файл
 * появляется мимо индекса легко — его кладут руками, копируют из соседнего проекта, приносят
 * мержем; после этого он лежит на диске и не существует ни для библиотекаря, ни для агента, ни для
 * человека, который ищет по индексу.
 *
 * Чистая: два списка имён внутрь, список сирот наружу.
 */
object CorpusIntegrity {
  /**
   * @param files имена файлов в папке
   * @param indexed имена, на которые ссылается индекс
   * @param indexName имя самого индекса — он на себя не ссылается и сиротой не является
   */
  fun orphans(files: List<String>, indexed: Collection<String>, indexName: String): List<String> {
    val known = indexed.map { it.substringAfterLast('/') }.toSet()
    return files.filter { it.endsWith(".md") && it != indexName && it !in known }.sorted()
  }
}
