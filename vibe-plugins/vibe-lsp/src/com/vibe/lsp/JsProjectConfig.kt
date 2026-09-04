// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

/**
 * Нужен ли проекту `jsconfig.json` и что в него написать.
 *
 * Зачем вообще: PhpStorm понимает JavaScript своим движком и индексирует ВСЕ `.js` проекта —
 * присваивание `Ext6 = …` он считает объявлением и находит его где угодно. У нас JavaScript живёт
 * через `vtsls`, а tsserver работает не «со всеми файлами», а с **программой**: без `jsconfig.json`
 * он собирает вокруг открытого файла «выведенный проект» из него самого и того, что достижимо через
 * `import`. Кодовая база со своим загрузчиком классов (ExtJS, старые сборки, глобалы в `window`)
 * импортов не имеет — и половина проекта для сервера не существует.
 *
 * Диагноз IDE знает лучше человека: «переход к определению не работает» выглядит как поломка IDE, а
 * на деле это отсутствующий файл на семь строк.
 *
 * Чистый: на входе описание проекта, на выходе решение и текст файла.
 */
object JsProjectConfig {
  /** Имена, при которых сервер уже настроен и лезть не надо. */
  val EXISTING_CONFIGS = listOf("jsconfig.json", "tsconfig.json")

  /**
   * Каталоги, которые исключаем всегда: чужой код и сборки.
   *
   * Их включение не даёт ничего (свои объявления там не живут), зато tsserver честно прочитает
   * сотни мегабайт и будет думать на каждом нажатии.
   */
  val VENDOR_DIRS = listOf(
    "node_modules", "vendor", "bower_components", "dist", "build", "out", "target",
    "coverage", ".git", ".idea", ".vscode", ".vibe", "docs", "tmp", "temp",
  )

  /** Каталоги вендорных библиотек по имени: `extjs`, `extjs4`, `extjs6`, `jquery-3.6`. */
  private val VENDOR_PREFIXES = listOf("extjs", "jquery", "bootstrap", "dojo", "yui", "sencha", "ckeditor")

  fun isVendor(dir: String): Boolean {
    val name = dir.trim('/').substringAfterLast('/').lowercase()
    return name in VENDOR_DIRS || VENDOR_PREFIXES.any { name.startsWith(it) }
  }

  /** Что мы знаем о проекте, чтобы принять решение. Всё считает вызывающий — здесь только логика. */
  data class Project(
    /** Есть ли уже `jsconfig.json` или `tsconfig.json` в корне. */
    val hasConfig: Boolean,
    /** Сколько `.js`-файлов НЕ в вендорных каталогах. */
    val ownJsFiles: Int,
    /** Каталоги верхнего уровня, где эти файлы лежат. */
    val sourceDirs: List<String>,
    /** Вендорные каталоги верхнего уровня — их исключаем поимённо. */
    val vendorDirs: List<String>,
  )

  /**
   * Стоит ли предлагать файл.
   *
   * Порог не «хоть один файл»: в проекте с тремя скриптами выведенного проекта хватает, а
   * предложение на пустом месте учит отмахиваться от предложений вообще.
   */
  const val MIN_FILES = 20

  fun needsConfig(project: Project): Boolean =
    !project.hasConfig && project.ownJsFiles >= MIN_FILES && project.sourceDirs.isNotEmpty()

  /**
   * Содержимое `jsconfig.json`.
   *
   * `checkJs` выключен намеренно: включённый он засыпает старую кодовую базу тысячами ошибок типов
   * в первый же день, и человек выключает уже весь сервер. Задача файла — дать серверу увидеть
   * файлы, а не начать их судить.
   */
  fun content(project: Project): String {
    val include = project.sourceDirs.distinct().sorted().map { "$it/**/*.js" }
    val exclude = (project.vendorDirs + VENDOR_DIRS).distinct().sorted()
    return buildString {
      appendLine("{")
      appendLine("  \"compilerOptions\": {")
      appendLine("    \"allowJs\": true,")
      appendLine("    \"checkJs\": false,")
      appendLine("    \"target\": \"es2017\",")
      appendLine("    \"moduleResolution\": \"node\"")
      appendLine("  },")
      appendLine(jsonArray("include", include) + ",")
      appendLine(jsonArray("exclude", exclude))
      appendLine("}")
    }
  }

  /** Список строк массивом JSON. Отдельно — чтобы висячая запятая была невозможна по построению. */
  private fun jsonArray(name: String, values: List<String>): String = buildString {
    append("  \"").append(name).append("\": [\n")
    append(values.joinToString(",\n") { "    \"" + it + "\"" })
    append("\n  ]")
  }
}
