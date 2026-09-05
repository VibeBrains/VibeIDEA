// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ingest

import kotlin.test.Test
import kotlin.test.assertEquals

/** Файл мимо индекса не существует ни для библиотекаря, ни для агента — его надо назвать. */
class CorpusIntegrityTest {
  @Test
  fun `файл без строки в индексе называется сиротой`() {
    val orphans = CorpusIntegrity.orphans(
      files = listOf("README.md", "0001-a.md", "0002-b.md"),
      indexed = listOf("docs/decisions/0001-a.md"),
      indexName = "README.md",
    )
    assertEquals(listOf("0002-b.md"), orphans)
  }

  @Test
  fun `сам индекс сиротой не бывает`() {
    assertEquals(emptyList(), CorpusIntegrity.orphans(listOf("README.md"), emptyList(), "README.md"))
  }

  @Test
  fun `не-markdown в папке не считается`() {
    assertEquals(emptyList(), CorpusIntegrity.orphans(listOf("contract.pdf", "notes.txt"), emptyList(), "README.md"))
  }

  @Test
  fun `порядок устойчив, чтобы отчёт не прыгал между запусками`() {
    assertEquals(
      listOf("a.md", "b.md", "c.md"),
      CorpusIntegrity.orphans(listOf("c.md", "a.md", "b.md"), emptyList(), "README.md"),
    )
  }
}
