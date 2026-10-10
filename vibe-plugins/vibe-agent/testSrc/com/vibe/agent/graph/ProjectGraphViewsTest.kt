// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graph

import com.vibe.agent.graph.CodeGraphIndex.Provenance
import com.vibe.agent.graph.ProjectGraphAnalysis.FileLink
import com.vibe.agent.graph.ProjectGraphViews.LinkFilter
import com.vibe.agent.graphview.CanvasNode
import com.vibe.agent.graphview.ForceLayout
import com.vibe.agent.graphview.GraphSizing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectGraphViewsTest {
  private fun fact(from: String, to: String) = FileLink(from, to, Provenance.FACT)
  private fun guess(from: String, to: String) = FileLink(from, to, Provenance.GUESS)

  private fun clique(prefix: String, size: Int) =
    (1..size).flatMap { i -> (i + 1..size).map { j -> fact("$prefix/f$i.ts", "$prefix/f$j.ts") } }

  private fun names(prefix: String, size: Int) = (1..size).map { "$prefix/f$it.ts" }

  /** `net` and `ui` of five files each, one fact bridge and one guess bridge between them */
  private val analysis = ProjectGraphAnalysis.analyze(
    names("net", 5) + names("ui", 5),
    clique("net", 5) + clique("ui", 5) + fact("net/f1.ts", "ui/f1.ts") + guess("net/f2.ts", "ui/f2.ts"),
  )
  private val net = analysis.subsystems.single { it.label == "net" }.id
  private val ui = analysis.subsystems.single { it.label == "ui" }.id

  @Test
  fun `the overview is one node per subsystem, joined once however many files link them`() {
    val graph = ProjectGraphViews.overview(analysis)
    assertEquals(setOf("net", "ui"), graph.nodes.map { it.label }.toSet())
    assertEquals(1, graph.edges.size, "two links between the subsystems are still one line on the map")
    assertEquals(ProjectGraphViews.OVERVIEW_LABELS_FROM_SCALE, graph.labelsFromScale)
  }

  @Test
  fun `a node is sized by the logarithm of the files it holds, and the count is in the tooltip`() {
    val graph = ProjectGraphViews.overview(analysis)
    val node = graph.nodes.first { it.label == "net" }
    assertEquals(GraphSizing.radiusOfCount(5), node.radius, 1e-9)
    assertTrue("5" in node.tooltip, "the file count stays in the hover text")
  }

  @Test
  fun `a search by a file name lights the subsystem it lives in`() {
    val node = ProjectGraphViews.overview(analysis).nodes.first { it.label == "ui" }
    assertTrue("ui/f3.ts" in node.searchText)
  }

  @Test
  fun `the link filter drops edges of the kind that is switched off`() {
    assertEquals(1, ProjectGraphViews.overview(analysis, LinkFilter(facts = true, guesses = false)).edges.size)
    assertEquals(1, ProjectGraphViews.overview(analysis, LinkFilter(facts = false, guesses = true)).edges.size)
    assertEquals(0, ProjectGraphViews.overview(analysis, LinkFilter(facts = false, guesses = false)).edges.size)
    // Colours must not jump under a filter: the subsystems are the same nodes whatever is switched on
    assertEquals(
      ProjectGraphViews.overview(analysis).nodes,
      ProjectGraphViews.overview(analysis, LinkFilter(facts = false, guesses = false)).nodes)
  }

  @Test
  fun `an opened subsystem shows its files and keeps the neighbour a single node`() {
    val view = ProjectGraphViews.subsystemView(analysis, net)
    val files = view.graph.nodes.filter { !it.aggregate }
    val neighbours = view.graph.nodes.filter { it.aggregate }
    assertEquals(names("net", 5).toSet(), files.map { it.id }.toSet())
    assertEquals(listOf(ProjectGraphViews.subsystemNodeId(ui)), neighbours.map { it.id })
    assertEquals(0, view.hiddenFiles)
    // 10 inside + 2 towards the neighbour (the fact from f1 and the guess from f2)
    assertEquals(12, view.graph.edges.size)
    assertEquals(ui, ProjectGraphViews.subsystemOfNodeId(neighbours.single().id))
    assertEquals(null, ProjectGraphViews.subsystemOfNodeId("net/f1.ts"))
  }

  @Test
  fun `facts only drops the guess towards the neighbour`() {
    val view = ProjectGraphViews.subsystemView(analysis, net, LinkFilter(facts = true, guesses = false))
    assertEquals(11, view.graph.edges.size)
  }

  @Test
  fun `a subsystem shows its most connected files up to the limit and says how many it left out`() {
    // A star of 500 leaves around one hub: one subsystem of 501 files, of which the picture holds the hub and the first leaves
    val leaves = (1..500).map { "big/leaf$it.ts" }
    val big = ProjectGraphAnalysis.analyze(listOf("big/hub.ts") + leaves, leaves.map { fact(it, "big/hub.ts") })
    val subsystem = big.subsystems.single()
    assertEquals("big/hub.ts", subsystem.files.first(), "the files of a subsystem are ordered by their links, the hub first")
    val view = ProjectGraphViews.subsystemView(big, subsystem.id)
    assertEquals(ProjectGraphViews.SUBSYSTEM_VIEW_LIMIT, view.graph.nodes.size)
    assertEquals(501 - ProjectGraphViews.SUBSYSTEM_VIEW_LIMIT, view.hiddenFiles)
    val shown = view.graph.nodes.map { it.id }.toSet()
    assertTrue("big/hub.ts" in shown)
    // Every edge that remains joins two nodes that remain
    assertEquals(ProjectGraphViews.SUBSYSTEM_VIEW_LIMIT - 1, view.graph.edges.size)
    assertTrue(view.graph.edges.all { it.from in shown && it.to in shown })
  }

  @Test
  fun `the limit is a parameter, not a magic number`() {
    val view = ProjectGraphViews.subsystemView(analysis, net, limit = 3)
    assertEquals(3, view.graph.nodes.count { !it.aggregate })
    assertEquals(2, view.hiddenFiles)
  }

  @Test
  fun `an unknown subsystem is an empty picture, not a crash`() {
    val view = ProjectGraphViews.subsystemView(analysis, 9999)
    assertTrue(view.graph.nodes.isEmpty())
    assertEquals(0, view.hiddenFiles)
  }

  @Test
  fun `the layout of a hub with hundreds of files stays finite and bounded`() {
    // The hub of the view above, laid out for real: mass by the springs of the picture and the speed cap keep it on the canvas
    val leaves = (1..400).map { "big/leaf$it.ts" }
    val big = ProjectGraphAnalysis.analyze(listOf("big/hub.ts") + leaves, leaves.map { fact(it, "big/hub.ts") })
    val graph = ProjectGraphViews.subsystemView(big, big.subsystems.single().id).graph
    val layout = ForceLayout(graph)
    repeat(300) { layout.step() }
    for (i in graph.nodes.indices) {
      val x = layout.positionX(i)
      val y = layout.positionY(i)
      assertTrue(x.isFinite() && y.isFinite(), "node $i is not a number")
      assertTrue(Math.hypot(x, y) < 5000, "node $i flew to ${Math.hypot(x, y)}")
    }
  }

  @Test
  fun `colour keys are shared by a subsystem and its files`() {
    val graph = ProjectGraphViews.subsystemView(analysis, net).graph
    val keys: Map<Boolean, Set<String>> = graph.nodes.groupBy({ it.aggregate }, CanvasNode::colorKey).mapValues { it.value.toSet() }
    assertEquals(setOf(ProjectGraphViews.colorKeyOf(net)), keys[false])
    assertEquals(setOf(ProjectGraphViews.colorKeyOf(ui)), keys[true])
  }
}
