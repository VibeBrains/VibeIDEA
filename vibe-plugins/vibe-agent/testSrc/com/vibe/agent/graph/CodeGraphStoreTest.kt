// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.graph

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeGraphStoreTest {
  private val fingerprint = CodeGraphStore.Fingerprint(size = 100, modifiedAtMs = 1_800_000_000_000L)

  private fun stored(path: String, symbols: List<String> = emptyList(), imports: List<String> = emptyList(),
                     fp: CodeGraphStore.Fingerprint = fingerprint) =
    CodeGraphStore.StoredNode(GraphNode(path, symbols, imports, emptyList()), fp)

  @Test
  fun `an unchanged file is reused, a changed one is re-parsed`() {
    val previous = listOf(stored("a.kt"), stored("b.kt"))
    val current = mapOf(
      "a.kt" to fingerprint,
      "b.kt" to fingerprint.copy(modifiedAtMs = fingerprint.modifiedAtMs + 1),
    )
    val (reused, stale) = CodeGraphStore.plan(current, previous)
    assertEquals(listOf("a.kt"), reused.map { it.path })
    assertEquals(listOf("b.kt"), stale)
  }

  @Test
  fun `a file of the same age but a different size is re-parsed`() {
    val (reused, stale) = CodeGraphStore.plan(mapOf("a.kt" to fingerprint.copy(size = 101)), listOf(stored("a.kt")))
    assertTrue(reused.isEmpty())
    assertEquals(listOf("a.kt"), stale)
  }

  @Test
  fun `a new file is parsed and a deleted one simply disappears`() {
    val (reused, stale) = CodeGraphStore.plan(mapOf("new.kt" to fingerprint), listOf(stored("gone.kt")))
    assertTrue(reused.isEmpty())
    assertEquals(listOf("new.kt"), stale)
  }

  @Test
  fun `the export round-trips, importers and provenance included`() {
    val nodes = listOf(
      stored("a/App.kt", symbols = listOf("a.App"), imports = listOf("b.Service")),
      stored("b/Service.kt", symbols = listOf("b.Service")),
    )
    val graph = CodeGraphIndex.build(nodes.map { it.node })
    val text = CodeGraphStore.encode(nodes, graph)

    assertTrue(text.contains("\"importers\""), text.take(400))
    assertTrue(text.contains("факт"), "происхождение связи обязано быть видно агенту")

    val decoded = CodeGraphStore.decode(text)
    assertEquals(nodes.map { it.node.path }, decoded.map { it.node.path })
    assertEquals(fingerprint, decoded.first().fingerprint)
  }

  @Test
  fun `an export of an older format yields nothing to reuse instead of failing`() {
    assertTrue(CodeGraphStore.decode("""{"version":1,"nodes":[{"path":"a.kt"}]}""").isEmpty())
    assertTrue(CodeGraphStore.decode("не json").isEmpty())
  }
}
