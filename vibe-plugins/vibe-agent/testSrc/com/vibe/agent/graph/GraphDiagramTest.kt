// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphDiagramTest {
  @Test
  fun `a module is the first meaningful folder, not the wrapper everyone has`() {
    // src/main/kotlin ничего не говорит о проекте: по нему все модули были бы одним.
    assertEquals("agent", GraphDiagram.moduleOf("src/main/kotlin/agent/Panel.kt"))
    assertEquals("ui", GraphDiagram.moduleOf("app/ui/Button.tsx"))
    assertEquals(GraphDiagram.ROOT, GraphDiagram.moduleOf("README.md"))
  }

  @Test
  fun `edges inside one module are not drawn`() {
    // Стрелка модуля в себя — это шум: она есть у всех и не говорит ничего.
    val edges = GraphDiagram.modules(listOf("ui/A.kt" to "ui/B.kt"))
    assertTrue(edges.isEmpty())
  }

  @Test
  fun `repeated edges become one with a weight`() {
    val edges = GraphDiagram.modules(listOf(
      "ui/A.kt" to "http/Api.kt",
      "ui/B.kt" to "http/Api.kt",
      "http/Api.kt" to "util/X.kt",
    ))
    assertEquals(GraphDiagram.Edge("ui", "http", 2), edges.first())
    assertEquals(2, edges.size)
  }

  @Test
  fun `the heaviest dependencies come first`() {
    val edges = GraphDiagram.modules(listOf(
      "a/1.kt" to "b/1.kt",
      "c/1.kt" to "d/1.kt", "c/2.kt" to "d/2.kt", "c/3.kt" to "d/3.kt",
    ))
    assertEquals("c", edges.first().from)
  }

  @Test
  fun `an empty graph yields no diagram rather than an empty picture`() {
    assertEquals("", GraphDiagram.mermaid(emptyList()))
  }

  @Test
  fun `a folder with a hyphen does not break the syntax`() {
    // Диаграмма, которая не рендерится, хуже отсутствующей.
    val text = GraphDiagram.mermaid(listOf(GraphDiagram.Edge("my-app", "core", 1)))
    assertTrue(text.contains("my_app[\"my-app\"]"))
    assertFalse(text.contains("my-app -->"))
  }

  @Test
  fun `a diagram is capped so it stays readable`() {
    val many = (1..50).map { GraphDiagram.Edge("from$it", "to$it", 1) }
    val text = GraphDiagram.mermaid(many, maxNodes = 6, maxEdges = 10)
    assertTrue(text.lines().count { it.contains("-->") } <= 10)
    assertTrue(text.lines().count { it.contains("[\"") } <= 6)
  }

  @Test
  fun `an id never starts with a digit`() {
    assertEquals("m2core", GraphDiagram.id("2core"))
    assertEquals("root", GraphDiagram.id("."))
  }
}
