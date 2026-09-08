// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.docs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DocsGraphLayoutTest {
  private val project = mapOf(
    "README.md" to "# Проект\n[руководство](docs/guide.md) и [план](docs/roadmap.md)",
    "docs/guide.md" to "# Руководство\n[глубже](deep.md), [в никуда](gone.md)",
    "docs/deep.md" to "# Глубоко",
    "docs/roadmap.md" to "# План",
    "docs/orphan.md" to "# Сирота",
  )

  private val analysis = DocsIndex.analyse(project)

  @Test
  fun `глубина считается по кратчайшему пути, а не по порядку обхода`() {
    val depths = DocsGraphLayout.depths(analysis)
    assertEquals(0, depths["README.md"])
    assertEquals(1, depths["docs/guide.md"])
    assertEquals(1, depths["docs/roadmap.md"])
    assertEquals(2, depths["docs/deep.md"])
    assertTrue("docs/orphan.md" !in depths, "сироту не достичь по ссылкам — глубины у неё нет")
  }

  @Test
  fun `степень считается по связям в обе стороны`() {
    // По ней раскладка берёт массу, а рисование — радиус: узел, который держат три нити, не
    // должен выглядеть как лист, куда никто не ведёт.
    val graph = DocsGraphLayout.layout(analysis)
    val entry = assertNotNull(graph.nodes.firstOrNull { it.path == "README.md" })
    val orphan = assertNotNull(graph.nodes.firstOrNull { it.path == "docs/orphan.md" })
    assertEquals(2, entry.degree, "вход ссылается на два документа")
    assertEquals(0, orphan.degree, "сироту не держит ничто")
  }

  @Test
  fun `категория — верхняя папка внутри папки документации`() {
    // По ней берётся цвет; «docs» в имени категории одинаков у всех и не различал бы ничего.
    assertEquals("knowledge", DocsGraphLayout.categoryOf("docs/knowledge/ai/x.md", "docs/README.md"))
    assertEquals("", DocsGraphLayout.categoryOf("docs/README.md", "docs/README.md"))
    assertEquals("manuals", DocsGraphLayout.categoryOf("manuals/deploy.md", "README.md"))
  }

  @Test
  fun `короткое имя узла — имя файла без расширения`() {
    // Заголовок «Документация VibeReel» на графе не читается, а имя файла опознаётся сразу.
    val graph = DocsGraphLayout.layout(analysis)
    assertEquals("guide", assertNotNull(graph.nodes.firstOrNull { it.path == "docs/guide.md" }).name)
  }

  @Test
  fun `битые ссылки — свойство страницы, а не ребро в пустоту`() {
    val graph = DocsGraphLayout.layout(analysis)
    val guide = assertNotNull(graph.nodes.firstOrNull { it.path == "docs/guide.md" })
    assertEquals(1, guide.brokenLinks)
    assertTrue(graph.edges.none { it.to == "docs/gone.md" }, "ребро в несуществующий узел нечем нарисовать")
  }

  @Test
  fun `все рёбра ведут в нарисованные узлы`() {
    val graph = DocsGraphLayout.layout(analysis)
    val drawn = graph.nodes.map { it.path }.toSet()
    assertTrue(graph.edges.all { it.from in drawn && it.to in drawn })
    assertTrue(graph.edges.isNotEmpty())
  }

  @Test
  fun `предел узлов режет дальние слои и говорит, сколько скрыто`() {
    val graph = DocsGraphLayout.layout(analysis, maxNodes = 2)
    assertEquals(2, graph.nodes.size)
    assertEquals("README.md", graph.nodes.first().path, "ближние слои — те, по которым ходят")
    assertEquals(3, DocsGraphLayout.droppedCount(analysis, maxNodes = 2))
    assertEquals(0, DocsGraphLayout.droppedCount(analysis, maxNodes = 120))
  }

  @Test
  fun `пустой набор документов не роняет раскладку`() {
    val empty = DocsIndex.analyse(emptyMap())
    val graph = DocsGraphLayout.layout(empty)
    assertTrue(graph.nodes.isEmpty() && graph.edges.isEmpty())
    // Размера полотна у модели больше нет и быть не может: координаты считает силовая раскладка,
    // и границы рисунка меняются на каждом шаге симуляции. Ноль здесь честнее выдуманного числа.
    assertEquals(0, graph.width)
    assertEquals(0, graph.height)
  }

}
