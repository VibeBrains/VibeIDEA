// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.docs

/**
 * Почему «недостижимо» может быть правдой и при этом бесполезным числом.
 *
 * Реальный случай (VibeReel, 08.09.2026): «Документов: 31 · недостижимо: 30». Формально верно —
 * из входа не ведёт ни одной ссылки. По сути бесполезно: индекс там ЕСТЬ, он аккуратно перечисляет
 * все файлы деревом внутри блока кода. Человек видит опрятный README и цифру «недостижимо: 30»,
 * не находит в них связи и перестаёт верить панели.
 *
 * Разница настоящая, и её надо назвать словами: перечисление — не ссылка. По имени файла в блоке
 * кода нельзя кликнуть, его не проверяет ни один инструмент, и при переименовании оно молча
 * устаревает. Но это ОДНА правка (обернуть имена в ссылки), а не тридцать потерянных документов, и
 * подсказка обязана сказать именно это.
 *
 * Чистая: на вход то же «путь → содержимое», что и у анализа.
 */
object DocsHints {
  /**
   * Документы, которые вход ПЕРЕЧИСЛЯЕТ, но не связывает ссылкой.
   *
   * Считается по имени файла, а не по полному пути: индексы пишут и так, и так («deploy.md» в
   * дереве против «manuals/deploy.md»), а ошибиться в сторону подсказки дешевле, чем промолчать.
   * Найденное — повод сказать «оберните в ссылки», а не диагноз.
   */
  fun mentionedButNotLinked(files: Map<String, String>, analysis: DocsIndex.Analysis, entry: String): List<String> {
    val text = files[entry] ?: return emptyList()
    val linked = analysis.docs.firstOrNull { it.path == entry }?.outgoing?.map { it.to }?.toSet().orEmpty()
    return analysis.unreachable
      .map { it.path }
      .filter { it != entry && it !in linked }
      .filter { path -> text.contains(path) || text.contains(path.substringAfterLast('/')) }
  }

  /**
   * Стоит ли вообще заводить разговор.
   *
   * Порог не «хотя бы один»: одинокое упоминание в тексте — обычное дело и ни о чём не говорит.
   * Подсказка появляется, когда перечислено БОЛЬШИНСТВО недостижимого, — тогда это точно индекс,
   * написанный не ссылками, а списком.
   */
  fun looksLikeUnlinkedIndex(mentioned: List<String>, unreachable: Int): Boolean =
    unreachable >= MIN_UNREACHABLE && mentioned.size * 2 >= unreachable

  /** Меньше трёх недостижимых — это не «индекс без ссылок», а просто пара забытых файлов. */
  private const val MIN_UNREACHABLE = 3
}
