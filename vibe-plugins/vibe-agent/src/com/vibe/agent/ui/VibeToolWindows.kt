// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

/**
 * Идентификаторы наших панелей — в одном месте и только ASCII.
 *
 * Две причины, обе выяснены на живом проекте:
 *
 * 1. **Идентификатор — не подпись.** Он уезжает в `.idea/workspace.xml`, в раскладку окон и в чужие
 *    конфигурации; русская буква там означает файл, который ломается при смене кодировки. Видимое
 *    имя приходит из каталога строк и переводится, идентификатор — нет.
 * 2. **Строкой в пяти местах он однажды разойдётся.** `"VibeAgent"` уже был написан руками в пяти
 *    файлах: два раза константой, три — литералом.
 *
 * Гейт `checkVibeUi.sh` требует, чтобы каждый `toolWindow id` из наших `plugin.xml` был объявлен
 * здесь, и чтобы в коде не осталось литералов с этими именами.
 */
object VibeToolWindows {
  const val AGENT = "VibeAgent"
  const val AUDIT = "VibeAudit"
  const val DESIGN = "VibeDesign"
  const val DOCS = "VibeDocs"
  const val RUNS = "VibeRuns"
  const val TASKS = "VibeTasks"
  const val SERVER = "VibeServer"
  const val HTTP = "VibeHttp"
  const val DB = "VibeDb"

  val ALL: List<String> = listOf(AGENT, AUDIT, DESIGN, DOCS, RUNS, TASKS, SERVER, HTTP, DB)
}
