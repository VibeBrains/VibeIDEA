// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Когда поднимать сервер Tailwind.
 *
 * Ошибка в любую сторону заметна: лишний сервер — сотня мегабайт памяти в проекте, которому он не
 * нужен; пропущенный — отсутствие подсказки классов там, ради чего он и взят.
 */
class TailwindConfigTest {
  @Test
  fun `a config file is enough`() {
    listOf("tailwind.config.js", "tailwind.config.cjs", "tailwind.config.mjs", "tailwind.config.ts").forEach {
      assertTrue(TailwindConfig.isTailwindProject(listOf("src", it), null), it)
    }
  }

  @Test
  fun `tailwind 4 has no config file, so the package is the sign`() {
    // В четвёртой версии подключение живёт прямо в CSS, и единственный честный признак — пакет.
    assertTrue(TailwindConfig.isTailwindProject(listOf("src"), """{"devDependencies": {"tailwindcss": "^4.1.0"}}"""))
  }

  @Test
  fun `a project without tailwind does not start the server`() {
    assertFalse(TailwindConfig.isTailwindProject(listOf("src", "package.json"), """{"dependencies": {"vue": "3"}}"""))
    assertFalse(TailwindConfig.isTailwindProject(emptyList(), null))
  }

  @Test
  fun `a mention in prose is not a sign`() {
    // Слово в описании пакета — не зависимость: искать надо ключ, а не подстроку в любом месте.
    assertFalse(TailwindConfig.isTailwindProject(listOf("package.json"), """{"description": "похоже на tailwindcss"}"""))
  }
}
