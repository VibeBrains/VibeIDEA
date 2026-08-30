// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.knowledge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibrarianTest {
  private val index = """
    # База знаний

    ## build

    | Файл | О чём |
    |---|---|
    | [bazelBuild.md](build/bazelBuild.md) | Сборка через Bazel: тулчейн герметичный, инсталляторы, цели тестов |
    | [caseInsensitiveFs.md](gitAndTools/caseInsensitiveFs.md) | Регистронезависимая файловая система перетирает файлы апстрима |

    ## agents

    - [contextPoisoning.md](agents/contextPoisoning.md) — отравление контекста, невидимые символы и фразы-инъекции
    - [Внешняя ссылка](https://example.com) — сюда ходить не надо
  """.trimIndent()

  @Test
  fun `both table rows and list items are entries`() {
    val entries = Librarian.parseIndex(index)
    assertEquals(3, entries.size)
    assertTrue(entries.any { it.path == "build/bazelBuild.md" })
    assertTrue(entries.any { it.path == "agents/contextPoisoning.md" })
  }

  @Test
  fun `external links are not knowledge entries`() {
    assertTrue(Librarian.parseIndex(index).none { it.path.startsWith("http") })
  }

  @Test
  fun `a request finds the entry written about it`() {
    val hits = Librarian.find(Librarian.parseIndex(index), "почему сборка через bazel падает на тулчейне?")
    assertEquals("build/bazelBuild.md", hits.first().entry.path)
  }

  @Test
  fun `an unrelated request finds nothing rather than the closest thing`() {
    // Назвать «ближайшую» запись на пустом месте — способ отучить читать подсказки библиотекаря.
    assertTrue(Librarian.find(Librarian.parseIndex(index), "как приготовить борщ").isEmpty())
  }

  @Test
  fun `short words do not match everything`() {
    assertTrue(Librarian.wordsOf("и в на о").isEmpty())
    assertTrue(Librarian.find(Librarian.parseIndex(index), "и в на о").isEmpty())
  }

  @Test
  fun `at most a few entries are named`() {
    val many = (1..20).joinToString("\n") { "- [файл$it.md](a/файл$it.md) — сборка bazel тулчейн инсталляторы" }
    val hits = Librarian.find(Librarian.parseIndex(many), "сборка bazel тулчейн")
    assertEquals(Librarian.MAX_HITS, hits.size)
  }

  @Test
  fun `the prompt block names paths, not contents`() {
    val hits = Librarian.find(Librarian.parseIndex(index), "сборка bazel тулчейн инсталляторы")
    val block = Librarian.promptBlock(hits, "Уже записано по теме:")
    assertTrue(block.contains("build/bazelBuild.md"))
    assertTrue(block.startsWith("Уже записано по теме:"))
  }

  @Test
  fun `no hits means no block at all`() {
    assertEquals("", Librarian.promptBlock(emptyList(), "Уже записано:"))
  }
}
