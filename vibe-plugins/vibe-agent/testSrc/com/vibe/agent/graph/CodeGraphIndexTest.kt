// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.graph

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeGraphIndexTest {
  private fun node(path: String, symbols: List<String> = emptyList(), imports: List<String> = emptyList()) =
    GraphNode(path, symbols, imports, emptyList())

  @Test
  fun `an import matching a declared qualified name is a fact`() {
    val graph = CodeGraphIndex.build(listOf(
      node("a/App.kt", symbols = listOf("a.App"), imports = listOf("b.Service")),
      node("b/Service.kt", symbols = listOf("b.Service")),
    ))
    val edge = graph.edges.single()
    assertEquals("a/App.kt" to "b/Service.kt", edge.from to edge.to)
    assertEquals(CodeGraphIndex.Provenance.FACT, edge.provenance)
  }

  @Test
  fun `a tail-only match is a guess, and an agent must be able to see the difference`() {
    val graph = CodeGraphIndex.build(listOf(
      node("a/App.kt", imports = listOf("some.other.Service")),
      node("b/Service.kt", symbols = listOf("b.Service")),
    ))
    assertEquals(CodeGraphIndex.Provenance.GUESS, graph.edges.single().provenance)
  }

  @Test
  fun `an ambiguous simple name yields no edge at all`() {
    // Two files declare Utils: a link to either one would be a coin toss dressed as knowledge.
    val graph = CodeGraphIndex.build(listOf(
      node("a/App.kt", imports = listOf("unknown.Utils")),
      node("b/Utils.kt", symbols = listOf("b.Utils")),
      node("c/Utils.kt", symbols = listOf("c.Utils")),
    ))
    assertTrue(graph.edges.isEmpty())
  }

  @Test
  fun `importers answer the question a file cannot answer about itself`() {
    val graph = CodeGraphIndex.build(listOf(
      node("a/App.kt", symbols = listOf("a.App"), imports = listOf("b.Service")),
      node("c/Job.kt", symbols = listOf("c.Job"), imports = listOf("b.Service")),
      node("b/Service.kt", symbols = listOf("b.Service")),
    ))
    assertEquals(setOf("a/App.kt", "c/Job.kt"), graph.importersOf("b/Service.kt").map { it.from }.toSet())
    assertEquals(listOf("b/Service.kt"), graph.importsOf("a/App.kt").map { it.to })
  }

  @Test
  fun `a wildcard or semicolon in the import line does not break matching`() {
    val graph = CodeGraphIndex.build(listOf(
      node("a/App.java", imports = listOf("b.Service;")),
      node("b/Service.java", symbols = listOf("b.Service")),
    ))
    assertEquals(CodeGraphIndex.Provenance.FACT, graph.edges.single().provenance)
  }

  @Test
  fun `a file importing its own symbol makes no self-edge`() {
    val graph = CodeGraphIndex.build(listOf(node("a/App.kt", symbols = listOf("a.App"), imports = listOf("a.App"))))
    assertTrue(graph.edges.isEmpty())
  }

  @Test
  fun `the chain between two files ignores direction`() {
    val graph = CodeGraphIndex.build(listOf(
      node("a.kt", symbols = listOf("A"), imports = listOf("B")),
      node("b.kt", symbols = listOf("B"), imports = listOf("C")),
      node("c.kt", symbols = listOf("C")),
    ))
    assertEquals(listOf("a.kt", "b.kt", "c.kt"), graph.path("a.kt", "c.kt"))
    assertEquals(listOf("c.kt", "b.kt", "a.kt"), graph.path("c.kt", "a.kt"), "связь спрашивают, а не зависимость")
  }

  @Test
  fun `no path is an answer, and a cycle does not hang the search`() {
    val graph = CodeGraphIndex.build(listOf(
      node("a.kt", symbols = listOf("A"), imports = listOf("B")),
      node("b.kt", symbols = listOf("B"), imports = listOf("A")),
      node("lonely.kt", symbols = listOf("L")),
    ))
    assertTrue(graph.path("a.kt", "lonely.kt").isEmpty())
    assertEquals(listOf("a.kt"), graph.path("a.kt", "a.kt"))
  }
}
