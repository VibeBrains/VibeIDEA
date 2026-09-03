// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.util

/**
 * Как на самом деле называется программа, которую человек назвал одним словом.
 *
 * На Unix «npx» — это файл `npx`, и вопроса нет. На Windows рядом лежат сразу несколько файлов с
 * этим именем: `npx` (shell-скрипт для Git Bash), `npx.cmd` (для cmd.exe) и `npx.ps1` (для
 * PowerShell). Запускать надо `.cmd`, а `Files.isExecutable` считает исполняемым и первый — на
 * Windows этот метод отвечает про право чтения, а не про формат файла.
 *
 * Поймано на живой машине: `Cannot run program "C:\Program Files\nodejs\npx": CreateProcess
 * error=193, %1 не является приложением Win32`. Ошибка называет симптом и молчит о причине, а
 * причина в том, что мы выбрали файл для чужой оболочки.
 *
 * Чистый: ОС и PATHEXT приходят параметрами, поэтому поведение Windows проверяется на macOS.
 */
object ExecutableNames {
  /** Порядок по умолчанию — тот же, что у cmd.exe, если PATHEXT не задан. */
  const val DEFAULT_PATHEXT = ".COM;.EXE;.BAT;.CMD"

  fun isWindows(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
    osName.lowercase().contains("win")

  /**
   * Уже путь, а не имя? Тогда искать по PATH нечего.
   *
   * Обратный слэш и буква диска — не мелочь: проверка только на `/` пропускала бы
   * `C:\tools\npx.cmd` в обход и отправляла бы его гулять по PATH.
   */
  fun looksLikePath(program: String): Boolean =
    program.contains('/') || program.contains('\\') || Regex("^[A-Za-z]:").containsMatchIn(program)

  /**
   * Имена, которые стоит попробовать, в порядке предпочтения.
   *
   * На Windows расширение идёт ПЕРВЫМ, а голое имя — последним: файл без расширения там почти
   * всегда скрипт для другой оболочки, но если ничего больше нет, пусть попытка провалится с
   * понятной ошибкой, а не молча пропадёт из списка.
   */
  fun candidates(
    program: String,
    windows: Boolean = isWindows(),
    pathext: String = System.getenv("PATHEXT") ?: DEFAULT_PATHEXT,
  ): List<String> {
    if (!windows) return listOf(program)
    // Уже с расширением — второй раз его не добавляем: «npx.cmd.cmd» не существует нигде.
    val hasExt = pathext.split(';').any { ext ->
      ext.isNotBlank() && program.endsWith(ext.trim(), ignoreCase = true)
    }
    if (hasExt) return listOf(program)
    val exts = pathext.split(';').map { it.trim() }.filter { it.isNotEmpty() }
    return exts.map { program + it } + program
  }
}
