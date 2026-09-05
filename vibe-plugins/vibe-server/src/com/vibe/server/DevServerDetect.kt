// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.server

/**
 * Дев-сервер проекта, угаданный по его собственным файлам.
 *
 * Панель стека без `.vibe/servers.json` не умела ничего — четыре кнопки и строка «создайте файл».
 * Между тем в подавляющем большинстве проектов ответ уже записан в `package.json`: скрипт `dev` и
 * менеджер пакетов, видимый по lock-файлу. Требовать за этим знанием отдельный конфиг — просить
 * человека переписать то, что у него уже есть.
 *
 * Догадка честно помечается догадкой и **не подменяет файл**: как только `servers.json` появился,
 * панель показывает только его. Иначе описанный руками стек молча соседствовал бы с придуманным.
 *
 * Чистая: содержимое `package.json` и список файлов корня внутрь, запись стека наружу.
 */
object DevServerDetect {
  /** Идентификатор угаданной записи. Отдельный от чужих id: файл может завести свой `dev`. */
  const val ID = "dev"

  /** Скрипты в порядке предпочтения — так же, как их ищет VibeIDE. */
  val SCRIPTS = listOf("dev", "start", "serve")

  /**
   * Готовность по строке лога, а не по порту: порт угадывать нельзя.
   *
   * Дев-серверы (Next, Vite, Angular, Rails) печатают адрес сами и сами же меняют порт, если
   * занят. Регулярка ловит любой `http://…:порт`, а панель берёт из этой строки адрес превью —
   * то есть узнаёт порт от сервера, вместо того чтобы его выдумать.
   */
  const val READY_PATTERN = "https?://[\\w.\\-]+:\\d+"

  /** Тяжёлый фронтенд поднимается минутами; 60 секунд по умолчанию пометили бы его упавшим. */
  const val READY_TIMEOUT_MS = 420_000L

  /** Менеджер пакетов по lock-файлу: запуск чужим менеджером переустанавливает зависимости. */
  private val LOCK_FILES = listOf(
    "pnpm-lock.yaml" to "pnpm",
    "yarn.lock" to "yarn",
    "bun.lockb" to "bun",
    "package-lock.json" to "npm",
  )

  fun packageManager(rootFiles: Collection<String>): String =
    LOCK_FILES.firstOrNull { it.first in rootFiles }?.second ?: "npm"

  /** Имена скриптов из `package.json`; разбор нарочно грубый — нам нужен только раздел `scripts`. */
  fun scriptsOf(packageJson: String?): List<String> {
    val text = packageJson ?: return emptyList()
    val start = text.indexOf("\"scripts\"")
    if (start < 0) return emptyList()
    val open = text.indexOf('{', start)
    if (open < 0) return emptyList()
    var depth = 0
    var end = open
    for (index in open until text.length) {
      when (text[index]) {
        '{' -> depth++
        '}' -> { depth--; if (depth == 0) { end = index; break } }
      }
    }
    return Regex("\"([^\"]+)\"\\s*:").findAll(text.substring(open, end)).map { it.groupValues[1] }.toList()
  }

  /**
   * Запись стека для угаданного сервера, или null — тогда угадывать нечего и врать не надо.
   */
  fun detect(packageJson: String?, rootFiles: Collection<String>): ServerEntry? {
    val scripts = scriptsOf(packageJson)
    val script = SCRIPTS.firstOrNull { it in scripts } ?: return null
    val manager = packageManager(rootFiles)
    return ServerEntry(
      id = ID,
      kind = "service",
      command = "$manager run $script",
      readyCheck = "log",
      readyPattern = READY_PATTERN,
      readyTimeoutMs = READY_TIMEOUT_MS,
    )
  }

  /** Адрес, напечатанный самим сервером, — из строки лога. */
  fun urlFrom(line: String): String? = Regex(READY_PATTERN).find(line)?.value
}
