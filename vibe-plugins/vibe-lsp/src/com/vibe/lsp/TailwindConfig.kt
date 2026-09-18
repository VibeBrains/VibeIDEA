// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import java.io.File

/**
 * Есть ли в проекте Tailwind — и стоит ли вообще поднимать его сервер.
 *
 * Сервер весит сотню мегабайт памяти и в проекте без Tailwind не делает ничего. Поднимать его
 * всегда значит брать эту сотню с каждого, кто открыл один `.html`; не поднимать никогда — отнять
 * подсказку классов у тех, ради кого он и взят. Поэтому решение принимается по признакам самого
 * проекта, как это делает расширение в другом редакторе.
 *
 * Признаки намеренно ДЕШЁВЫЕ: имена файлов конфигурации и упоминание пакета в `package.json`. Ни
 * одного чтения дерева и ни одного индекса — решение принимается на открытии файла, и стоить оно
 * должно миллисекунды.
 *
 * Чистая проверка вынесена отдельно от файловой системы: правило проверяется тестом, а не
 * созданием проекта на диске.
 */
object TailwindConfig {
  /** Имена, по которым Tailwind узнаётся без чтения содержимого. */
  private val CONFIG_NAMES = listOf(
    "tailwind.config.js", "tailwind.config.cjs", "tailwind.config.mjs", "tailwind.config.ts",
  )

  /** Достаточно ли этих признаков, чтобы поднимать сервер. */
  fun isTailwindProject(names: Collection<String>, packageJson: String?): Boolean {
    if (names.any { it in CONFIG_NAMES }) return true
    // Tailwind 4 обходится без файла конфигурации: он подключается прямо в CSS через `@import
    // "tailwindcss"`, поэтому единственный честный признак — сам пакет в зависимостях.
    return packageJson?.contains("\"tailwindcss\"") == true
  }

  /**
   * Ответ по проекту — с коротким кэшем.
   *
   * Вопрос задаётся на КАЖДОЕ открытие файла и на каждую проверку включённости сервера, а ответ на
   * него требует листинга каталога и чтения `package.json`. Диск в таком месте — это задержка,
   * которую человек замечает как «редактор задумался», и платить её за неизменный ответ незачем.
   *
   * Кэш короткий, а не вечный: Tailwind добавляют в проект посреди работы, и IDE не должна просить
   * перезапуска ради одной строки в `package.json`. Полминуты — столько, сколько человек всё равно
   * потратит на установку пакета.
   */
  fun isTailwindProject(projectBase: String?): Boolean {
    val base = projectBase ?: return false
    val now = System.currentTimeMillis()
    cache[base]?.let { (answer, at) -> if (now - at < CACHE_MS) return answer }
    val dir = File(base).takeIf { it.isDirectory } ?: return false
    val names = dir.list()?.toList() ?: emptyList()
    val packageJson = File(dir, "package.json").takeIf { it.isFile }
      ?.let { runCatching { it.readText() }.getOrNull() }
    val answer = isTailwindProject(names, packageJson)
    cache[base] = answer to now
    return answer
  }

  private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, Long>>()

  private const val CACHE_MS = 30_000L
}
