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
  fun `сироты живут своей полосой ниже всего достижимого`() {
    val graph = DocsGraphLayout.layout(analysis)
    val orphan = assertNotNull(graph.nodes.firstOrNull { it.path == "docs/orphan.md" })
    assertTrue(!orphan.reachable)
    val lowestReachable = graph.nodes.filter { it.reachable }.maxOf { it.y }
    assertTrue(orphan.y > lowestReachable, "сирота должна быть ниже всех достижимых, а не вперемешку")
  }

  @Test
  fun `узлы не накладываются друг на друга`() {
    val graph = DocsGraphLayout.layout(analysis)
    val positions = graph.nodes.map { it.x to it.y }
    assertEquals(positions.size, positions.toSet().size, "две страницы в одной точке — потерянная страница")
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
    assertTrue(graph.width > 0 && graph.height > 0)
  }

  @Test
  fun `размер полотна вмещает самый широкий слой`() {
    val graph = DocsGraphLayout.layout(analysis)
    val right = graph.nodes.maxOf { it.x + DocsGraphLayout.NODE_WIDTH }
    val bottom = graph.nodes.maxOf { it.y + DocsGraphLayout.NODE_HEIGHT }
    assertTrue(right <= graph.width, "узел за краем полотна не будет виден")
    assertTrue(bottom <= graph.height)
  }
}
