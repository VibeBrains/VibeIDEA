// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.docs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocsIndexTest {
  private val project = mapOf(
    "README.md" to "# Проект\n\nСмотри [план](roadmap.md) и [спеку](manuals/spec.md).",
    "roadmap.md" to "# План\n\nНазад в [README](README.md), внешняя [ссылка](https://example.com).",
    "manuals/spec.md" to "# Спека\n\nСоседний [файл](../roadmap.md) и [пропавший](../missing.md).",
    "orphan.md" to "# Сирота\n\nНа меня никто не ссылается.",
    "notes.txt" to "не markdown",
  )

  @Test
  fun `only markdown takes part in the graph`() {
    val analysis = DocsIndex.analyse(project)
    assertEquals(4, analysis.docs.size)
    assertTrue(analysis.docs.none { it.path == "notes.txt" })
  }

  @Test
  fun `a document nobody links to is found`() {
    // Документ, на который нет ссылок, — документ, который никто не найдёт, каким бы он ни был.
    assertEquals(listOf("orphan.md"), DocsIndex.analyse(project).unreachable.map { it.path })
  }

  @Test
  fun `a broken link is named with the file it is written in`() {
    val broken = DocsIndex.analyse(project).brokenLinks
    assertEquals(1, broken.size)
    assertEquals("manuals/spec.md", broken.single().from)
    assertEquals("missing.md", broken.single().to)
  }

  @Test
  fun `relative links resolve against the folder of their file`() {
    assertEquals("roadmap.md", DocsIndex.resolve("manuals/spec.md", "../roadmap.md"))
    assertEquals("manuals/deep/a.md", DocsIndex.resolve("manuals/spec.md", "deep/a.md"))
    assertEquals("top.md", DocsIndex.resolve("manuals/deep/spec.md", "../../top.md"))
    assertEquals("abs.md", DocsIndex.resolve("manuals/spec.md", "/abs.md"))
  }

  @Test
  fun `external links and anchors are not edges`() {
    val doc = DocsIndex.analyse(project).docs.first { it.path == "roadmap.md" }
    assertEquals(listOf("README.md"), doc.outgoing.map { it.to })
  }

  @Test
  fun `an anchor on a real file still counts as a link to that file`() {
    val files = mapOf("README.md" to "[раздел](guide.md#часть)", "guide.md" to "# Гид")
    assertTrue(DocsIndex.analyse(files).brokenLinks.isEmpty())
    assertTrue("guide.md" in DocsIndex.analyse(files).reachable)
  }

  @Test
  fun `a cycle does not hang the walk`() {
    val files = mapOf("README.md" to "[a](a.md)", "a.md" to "[b](b.md)", "b.md" to "[a](a.md)")
    assertEquals(setOf("README.md", "a.md", "b.md"), DocsIndex.analyse(files).reachable)
  }

  @Test
  fun `the title comes from the first heading, and falls back to the file name`() {
    val files = mapOf("README.md" to "# Заголовок", "plain.md" to "без заголовка")
    val docs = DocsIndex.analyse(files).docs
    assertEquals("Заголовок", docs.first { it.path == "README.md" }.title)
    assertEquals("plain.md", docs.first { it.path == "plain.md" }.title)
  }

  @Test
  fun `a project without a README starts from the first document rather than from nothing`() {
    // Иначе в проекте без README «недостижимо» было бы ВСЁ, и отчёт стал бы бесполезным.
    val files = mapOf("intro.md" to "[дальше](next.md)", "next.md" to "# Дальше")
    assertEquals(2, DocsIndex.analyse(files).reachable.size)
  }

  @Test
  fun `an empty project analyses to nothing without failing`() {
    val analysis = DocsIndex.analyse(emptyMap())
    assertTrue(analysis.docs.isEmpty())
    assertTrue(analysis.reachable.isEmpty())
    assertTrue(analysis.brokenLinks.isEmpty())
  }
}
