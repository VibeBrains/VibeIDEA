// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

/**
 * Есть ли у проекта свой ESLint — и стоит ли вообще поднимать сервер.
 *
 * Найдено на живой 0.4.1 (проект вообще без Node): сервер запускался всегда и на каждый запрос
 * отвечал «Request textDocument/codeAction failed: Cannot read properties of null», а IDE честно
 * показывала это уведомлением. Два раза подряд, при каждом открытии файла.
 *
 * Ошибка тут не одна: сервер не получал настроек (их запрашивают через `workspace/configuration`,
 * и `null` в ответе — та самая «null» в сообщении), и его запускали там, где линтить нечем.
 * Настройки чинит [EslintLanguageClient], а эта функция отвечает на второй вопрос: чужой линтер без
 * конфигурации проекта не имеет смысла, потому что правил у него нет и придумывать их нельзя.
 *
 * Чистая: список имён файлов корня внутрь, ответ наружу.
 */
object EslintConfig {
  /** Плоский конфиг (ESLint 9+) и все классические имена. */
  private val NAMES = listOf(
    "eslint.config.js", "eslint.config.mjs", "eslint.config.cjs", "eslint.config.ts",
    ".eslintrc", ".eslintrc.js", ".eslintrc.cjs", ".eslintrc.json", ".eslintrc.yml", ".eslintrc.yaml",
  )

  /**
   * @param rootFiles имена файлов в корне проекта
   * @param packageJson содержимое `package.json`, если он есть: конфиг живёт и внутри него
   */
  fun exists(rootFiles: Collection<String>, packageJson: String? = null): Boolean =
    NAMES.any { it in rootFiles } || packageJson?.contains("\"eslintConfig\"") == true
}
