// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graphview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The shared model: what counts as a spring, what a node weighs, how big a group is drawn */
class CanvasGraphTest {
  private fun node(id: String) = CanvasNode(id, id, id, id, "", GraphSizing.MIN_RADIUS)

  @Test
  fun `degrees are counted from the edges of this picture`() {
    val graph = CanvasGraph(listOf(node("hub"), node("a"), node("b"), node("c")), listOf(
      CanvasEdge("hub", "a"), CanvasEdge("hub", "b"), CanvasEdge("c", "hub")))
    assertEquals(listOf(3, 1, 1, 1), graph.degrees.toList())
  }

  @Test
  fun `an edge without both ends, or into itself, is not a spring`() {
    val graph = CanvasGraph(listOf(node("a"), node("b")), listOf(
      CanvasEdge("a", "b"), CanvasEdge("a", "gone"), CanvasEdge("gone", "b"), CanvasEdge("a", "a")))
    assertEquals(listOf(CanvasEdge("a", "b")), graph.edges)
    assertEquals(listOf(1, 1), graph.degrees.toList())
  }

  @Test
  fun `the same picture is equal to itself, a changed one is not`() {
    // The canvas keeps its layout and zoom when a refresh brings the very same picture back
    fun picture(extra: String) = CanvasGraph(listOf(node("a"), node("b"), node(extra)), listOf(CanvasEdge("a", "b")))
    assertEquals(picture("c"), picture("c"))
    assertEquals(picture("c").hashCode(), picture("c").hashCode())
    assertNotEquals(picture("c"), picture("d"))
  }

  @Test
  fun `a search matches the text of a node whatever the case, and an empty query matches none`() {
    val graph = CanvasGraph(
      listOf(
        CanvasNode("a", "a", "", "src/ui/panel.ts", "", 4.0),
        CanvasNode("b", "b", "", "src/net/client.ts", "", 4.0),
      ),
      emptyList())
    assertEquals(setOf(0), graph.matching("PANEL"))
    assertEquals(setOf(0, 1), graph.matching("  src/ "))
    assertEquals(emptySet(), graph.matching(""))
    assertEquals(emptySet(), graph.matching("   "))
    assertEquals(emptySet(), graph.matching("nothing like this"))
  }

  @Test
  fun `a group is drawn by the logarithm of its size, not linearly`() {
    val one = GraphSizing.radiusOfCount(1)
    val ten = GraphSizing.radiusOfCount(10)
    val thousand = GraphSizing.radiusOfCount(1000)
    val huge = GraphSizing.radiusOfCount(1_800)
    assertEquals(GraphSizing.MIN_RADIUS, one, 1e-9)
    assertTrue(one < ten && ten < thousand && thousand < huge, "bigger groups are bigger circles")
    // Linear, a group of 1800 would be 180 times a group of 10; by the logarithm it is about twice
    assertTrue(huge / ten < 3.0, "1800 files against 10 is ${huge / ten} times the radius")
    assertTrue(huge <= GraphSizing.MAX_RADIUS, "a group never outgrows the cap")
    assertEquals(GraphSizing.MIN_RADIUS, GraphSizing.radiusOfCount(0), 1e-9)
  }

  @Test
  fun `a name that looks like markup stays text in a tooltip and a label`() {
    // A cloned repository picks its own file names; Swing would read a leading html tag as a page, and fetch the images in it
    assertEquals("\u200B<html><img src=x>", PlainText.safe("<html><img src=x>"))
    assertEquals("src/ui/panel.ts", PlainText.safe("src/ui/panel.ts"))
    val label = PlainText.plain(javax.swing.JLabel("<html>x"))
    assertEquals(true, label.getClientProperty("html.disable"))
  }
}
