// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import java.io.File

/**
 * Пишут ли в этом проекте стили внутри шаблонных строк — и стоит ли включать плагин для них.
 *
 * Вопрос задаётся по той же причине, что и про Tailwind, но цена ошибки здесь другая. Плагины Vue
 * и Astro помечены своими языками и в обычный `.ts` не заглядывают; `typescript-styled-plugin`
 * помечен ничем — он оборачивает языковую службу для КАЖДОГО `.ts` и `.tsx`. Включать его всем
 * значит брать налог с проектов, которые о styled-components не слышали, ради возможности, которой
 * они не пользуются.
 *
 * Признак дешёвый и единственный: пакет в зависимостях. Ни чтения дерева, ни индекса — ответ
 * нужен один раз, на старте языкового сервера.
 *
 * Чистая проверка вынесена отдельно от файловой системы: правило проверяется тестом, а не
 * созданием проекта на диске.
 */
object StyledConfig {
  /**
   * Пакеты, любой из которых означает «стили живут в шаблонных строках».
   *
   * Emotion назван двумя пакетами не для полноты: `@emotion/styled` ставят ради `styled.div`, а
   * `@emotion/react` — ради `css`-пропа, и проект нередко тянет только второй. Умолчания тегов
   * плагина (`styled`, `css`, `extend`, `injectGlobal`, `createGlobalStyle`, `keyframes`)
   * покрывают оба случая, поэтому различать их незачем.
   */
  private val PACKAGES = listOf("styled-components", "@emotion/styled", "@emotion/react")

  /**
   * Достаточно ли содержимого `package.json`, чтобы включать плагин.
   *
   * Имя отличается от [isStyledProject] намеренно, а не ради разрешения перегрузок: одна функция
   * отвечает про ТЕКСТ, другая про ПРОЕКТ, и одинаковое имя у обеих читалось бы как «одно и то же
   * разными путями».
   */
  fun declaresStyled(packageJson: String?): Boolean {
    val text = packageJson ?: return false
    // Сравнение с кавычками, а не подстрокой: `styled-components-modifiers` в зависимостях не
    // делает проект проектом на styled-components, а совпадение по имени случилось бы молча.
    return PACKAGES.any { text.contains("\"$it\"") }
  }

  /** Ответ по проекту. Читается один раз — на создании клиента языкового сервера. */
  fun isStyledProject(projectBase: String?): Boolean {
    val base = projectBase?.takeIf { it.isNotBlank() } ?: return false
    val file = File(base, "package.json").takeIf { it.isFile } ?: return false
    return declaresStyled(runCatching { file.readText() }.getOrNull())
  }
}
