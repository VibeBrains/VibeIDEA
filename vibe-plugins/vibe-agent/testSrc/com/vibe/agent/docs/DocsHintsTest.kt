// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.docs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** «Недостижимо: 30» бывает правдой и бесполезным числом одновременно — случай надо назвать. */
class DocsHintsTest {
  private val indexAsCodeBlock = """
    # Документация

    ```
    docs/
    ├── functional.md   — каталог возможностей
    ├── roadmap.md      — что уже сделано
    └── manuals/
        └── deploy.md   — выкладка
    ```
  """.trimIndent()

  private val files = mapOf(
    "docs/README.md" to indexAsCodeBlock,
    "docs/functional.md" to "# Каталог",
    "docs/roadmap.md" to "# План",
    "docs/manuals/deploy.md" to "# Выкладка",
  )

  @Test
  fun `перечисленные списком документы узнаются`() {
    val analysis = DocsIndex.analyse(files, entryPoint = "docs/README.md")
    val mentioned = DocsHints.mentionedButNotLinked(files, analysis, "docs/README.md")
    assertEquals(listOf("docs/functional.md", "docs/manuals/deploy.md", "docs/roadmap.md"), mentioned.sorted())
    assertTrue(DocsHints.looksLikeUnlinkedIndex(mentioned, analysis.unreachable.size))
  }

  @Test
  fun `настоящие ссылки подсказку не вызывают`() {
    val linked = files + ("docs/README.md" to
      "# Документация\n\n- [Каталог](functional.md)\n- [План](roadmap.md)\n- [Выкладка](manuals/deploy.md)\n")
    val analysis = DocsIndex.analyse(linked, entryPoint = "docs/README.md")
    val mentioned = DocsHints.mentionedButNotLinked(linked, analysis, "docs/README.md")
    assertEquals(emptyList(), mentioned)
    assertFalse(DocsHints.looksLikeUnlinkedIndex(mentioned, analysis.unreachable.size))
  }

  @Test
  fun `пара забытых файлов — это не «индекс без ссылок»`() {
    // Порог существует, чтобы подсказка не появлялась там, где чинить надо не индекс, а забывчивость.
    assertFalse(DocsHints.looksLikeUnlinkedIndex(listOf("a.md", "b.md"), unreachable = 2))
  }

  @Test
  fun `меньшинство упомянутых подсказку не вызывает`() {
    // Десять недостижимых и два упоминания — это не список вместо ссылок, а совпадение.
    assertFalse(DocsHints.looksLikeUnlinkedIndex(listOf("a.md", "b.md"), unreachable = 10))
    assertTrue(DocsHints.looksLikeUnlinkedIndex(listOf("a.md", "b.md", "c.md", "d.md", "e.md"), unreachable = 10))
  }

  @Test
  fun `вход сам себя в подсказку не приносит`() {
    val analysis = DocsIndex.analyse(files, entryPoint = "docs/README.md")
    assertFalse(DocsHints.mentionedButNotLinked(files, analysis, "docs/README.md").contains("docs/README.md"))
  }
}
