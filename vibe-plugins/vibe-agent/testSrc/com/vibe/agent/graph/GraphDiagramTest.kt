// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graph

import com.vibe.agent.graph.CodeGraphIndex.Provenance
import com.vibe.agent.graph.ProjectGraphAnalysis.FileLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The text form of the subsystems, for the chat command `/map` */
class GraphDiagramTest {
  private fun fact(from: String, to: String) = FileLink(from, to, Provenance.FACT)

  private fun clique(prefix: String, size: Int = 6) =
    (1..size).flatMap { i -> (i + 1..size).map { j -> fact("$prefix/f$i.ts", "$prefix/f$j.ts") } }

  private fun names(prefix: String, size: Int = 6) = (1..size).map { "$prefix/f$it.ts" }

  /** `app` and `my-app` of six files, a single link between them, and `core` joined to `app` by three */
  private val analysis = ProjectGraphAnalysis.analyze(
    names("app") + names("my-app") + names("core"),
    clique("app") + clique("my-app") + clique("core") + fact("app/f1.ts", "my-app/f1.ts") +
      fact("core/f1.ts", "app/f2.ts") + fact("core/f2.ts", "app/f3.ts") + fact("core/f3.ts", "app/f4.ts"),
  )

  private fun id(label: String) = analysis.subsystems.single { it.label == label }.id

  @Test
  fun `links inside one subsystem are not drawn, links between two are counted once each`() {
    val edges = GraphDiagram.subsystems(analysis)
    assertEquals(2, edges.size)
    assertEquals(3, edges.single { setOf(it.from, it.to) == setOf(id("core"), id("app")) }.weight)
    assertEquals(1, edges.single { setOf(it.from, it.to) == setOf(id("my-app"), id("app")) }.weight)
  }

  @Test
  fun `the heaviest link comes first`() {
    assertEquals(listOf(3, 1), GraphDiagram.subsystems(analysis).map { it.weight })
  }

  @Test
  fun `no links between subsystems yields no diagram rather than an empty picture`() {
    assertEquals("", GraphDiagram.mermaid(analysis, emptyList()))
    val apart = ProjectGraphAnalysis.analyze(names("a") + names("b"), clique("a") + clique("b"))
    assertTrue(GraphDiagram.subsystems(apart).isEmpty())
  }

  @Test
  fun `a node id is made of the number, so a folder with a hyphen cannot break the syntax`() {
    val text = GraphDiagram.mermaid(analysis, GraphDiagram.subsystems(analysis))
    assertTrue(text.contains("${GraphDiagram.id(id("my-app"))}[\"my-app · 6\"]"), text)
    assertFalse(text.contains("my-app -->"))
    assertTrue(text.startsWith("flowchart LR"))
  }

  @Test
  fun `a weight shows on a line only when it says more than one`() {
    val text = GraphDiagram.mermaid(analysis, GraphDiagram.subsystems(analysis))
    assertEquals(1, text.lines().count { it.contains("-->|3|") })
    assertEquals(1, text.lines().count { it.contains("-->") && !it.contains("|") })
  }

  @Test
  fun `a quote in a label does not close the label early`() {
    val quoted = ProjectGraphAnalysis.analyze(
      names("say \"hi\"") + names("b"),
      clique("say \"hi\"") + clique("b") + fact("say \"hi\"/f1.ts", "b/f1.ts"),
    )
    val text = GraphDiagram.mermaid(quoted, GraphDiagram.subsystems(quoted))
    assertTrue(text.lines().filter { it.contains("[\"") }.all { it.count { c -> c == '"' } == 2 }, text)
  }

  @Test
  fun `a diagram is capped so it stays readable`() {
    val groups = (1..5).map { "g$it" }
    val ring = groups.indices.map { fact("${groups[it]}/f1.ts", "${groups[(it + 1) % groups.size]}/f2.ts") }
    val big = ProjectGraphAnalysis.analyze(groups.flatMap { names(it) }, groups.flatMap { clique(it) } + ring)
    assertEquals(5, big.subsystems.size)
    val text = GraphDiagram.mermaid(big, GraphDiagram.subsystems(big), maxNodes = 3, maxEdges = 10)
    assertTrue(text.lines().count { it.contains("[\"") } <= 3)
    assertTrue(text.lines().count { it.contains("-->") } <= 2)
  }
}
