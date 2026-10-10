// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.docs

import com.vibe.agent.graphview.GraphSizing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The docs graph on the shared canvas: what a document becomes there */
class DocsGraphCanvasTest {
  private fun node(path: String, degree: Int, category: String = "", reachable: Boolean = true, broken: Int = 0) =
    DocsGraphLayout.Node(
      path = path, title = "Title of $path", layer = 0, column = 0, degree = degree,
      category = category, reachable = reachable, brokenLinks = broken,
    )

  private val graph = DocsGraphLayout.Graph(
    nodes = listOf(
      node("docs/README.md", degree = 2),
      node("docs/knowledge/a.md", degree = 1, category = "knowledge"),
      node("docs/lost.md", degree = 0, reachable = false),
      node("docs/b.md", degree = 1, broken = 2),
    ),
    edges = listOf(DocsGraphLayout.Edge("docs/README.md", "docs/knowledge/a.md"), DocsGraphLayout.Edge("docs/README.md", "docs/b.md")),
    width = 0, height = 0,
  )

  @Test
  fun `every document becomes a node and every link an edge`() {
    val canvas = DocsGraphCanvas.toCanvas(graph)
    assertEquals(graph.nodes.map { it.path }, canvas.nodes.map { it.id })
    assertEquals(2, canvas.edges.size)
    assertEquals(listOf(2, 1, 0, 1), canvas.degrees.toList())
  }

  @Test
  fun `the caption is the file name without the extension and the tooltip names the title and the path`() {
    val node = DocsGraphCanvas.toCanvas(graph).nodes.first { it.id == "docs/knowledge/a.md" }
    assertEquals("a", node.label)
    assertEquals("Title of docs/knowledge/a.md   docs/knowledge/a.md", node.tooltip)
  }

  @Test
  fun `state outweighs the folder in the colour key`() {
    val keys = DocsGraphCanvas.toCanvas(graph).nodes.associate { it.id to it.colorKey }
    assertEquals(DocsGraphCanvas.ORPHAN, keys["docs/lost.md"])
    assertEquals(DocsGraphCanvas.BROKEN, keys["docs/b.md"])
    assertTrue(keys.getValue("docs/knowledge/a.md") != keys.getValue("docs/lost.md"))
  }

  @Test
  fun `a circle is sized by the degree, as before the canvas was shared`() {
    val canvas = DocsGraphCanvas.toCanvas(graph)
    assertEquals(GraphSizing.radiusOf(2), canvas.nodes[0].radius, 1e-9)
    assertEquals(GraphSizing.radiusOf(0), canvas.nodes[2].radius, 1e-9)
  }

  @Test
  fun `a query cannot match across the seam of the title and the path`() {
    val node = DocsGraphCanvas.toCanvas(graph).nodes[0]
    assertTrue("title of docs/readme.md" in node.searchText)
    assertTrue("docs/readme.md" in node.searchText)
  }
}
