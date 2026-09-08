// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.docs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Документы папками — как в дереве проекта, а не списком путей. */
class DocsTreeTest {
  private fun item(path: String, marks: List<String> = emptyList()) =
    DocsTree.Item(path, title = path.substringAfterLast('/'), marks = marks)

  @Test
  fun `файлы складываются в папки`() {
    val nodes = DocsTree.build(root = "docs", items = listOf(
      item("docs/README.md"),
      item("docs/manuals/deploy.md"),
      item("docs/manuals/devSetup.md"),
    ))
    assertEquals(listOf("manuals", "README"), nodes.map { it.name }, "папки выше файлов")
    assertEquals(listOf("deploy", "devSetup"), nodes.first().children.map { it.name }, "расширение .md в подписи не нужно — тип несёт значок")
    assertTrue(nodes.first().isFolder)
    assertEquals("docs/manuals/deploy.md", nodes.first().children.first().path)
  }

  @Test
  fun `цепочка одиночных папок склеивается`() {
    // knowledge → ai → один файл: три уровня отступа ради одного пути ничего не сообщают.
    val nodes = DocsTree.build(root = "docs", items = listOf(item("docs/knowledge/ai/structuredOutput.md")))
    assertEquals(listOf("knowledge/ai"), nodes.map { it.name })
    assertEquals(listOf("structuredOutput"), nodes.first().children.map { it.name })
  }

  @Test
  fun `папка с двумя ветками не склеивается`() {
    val nodes = DocsTree.build(root = "docs", items = listOf(
      item("docs/knowledge/ai/one.md"),
      item("docs/knowledge/ui/two.md"),
    ))
    assertEquals(listOf("knowledge"), nodes.map { it.name })
    assertEquals(listOf("ai", "ui"), nodes.first().children.map { it.name })
  }

  @Test
  fun `проблемы поддерева видны на папке`() {
    // Свёрнутая ветка обязана сказать, что внутри есть на что посмотреть.
    val nodes = DocsTree.build(root = "docs", items = listOf(
      item("docs/knowledge/ai/one.md", marks = listOf("недостижим")),
      item("docs/knowledge/ai/two.md", marks = listOf("недостижим", "битых ссылок 2")),
      item("docs/README.md"),
    ))
    val folder = nodes.first { it.isFolder }
    assertEquals(3, folder.problems)
    assertEquals(0, nodes.first { !it.isFolder }.problems)
  }

  @Test
  fun `пустой ввод даёт пустое дерево, а не корень-пустышку`() {
    assertEquals(emptyList(), DocsTree.build(emptyList(), root = "docs"))
  }

  @Test
  fun `корневая папка документации узлом не показывается`() {
    // Она одинакова у всех документов: узел «docs» тратил бы уровень отступа и не сообщал ничего.
    val nodes = DocsTree.build(listOf(item("docs/README.md")), root = "docs")
    assertEquals(listOf("README"), nodes.map { it.name })
    assertEquals("docs/README.md", nodes.first().path, "путь остаётся полным — по нему открывается файл")
  }

  @Test
  fun `порядок стабилен и не зависит от порядка обхода файлов`() {
    val one = DocsTree.build(root = "docs", items = listOf(item("docs/b.md"), item("docs/a.md"), item("docs/x/y.md")))
    val two = DocsTree.build(root = "docs", items = listOf(item("docs/x/y.md"), item("docs/a.md"), item("docs/b.md")))
    assertEquals(one.map { it.name }, two.map { it.name })
    assertEquals(listOf("x", "a", "b"), one.map { it.name })
  }

  @Test
  fun `mdx остаётся видимым`() {
    // Два разных формата не должны выглядеть одинаково: скрытое расширение соврало бы глазу.
    val nodes = DocsTree.build(listOf(item("docs/page.mdx"), item("docs/plain.md")), root = "docs")
    assertEquals(listOf("page.mdx", "plain"), nodes.map { it.name })
  }
}
