// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graph

import com.vibe.agent.graph.Communities.WeightedLink
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Subsystems by Leiden: the cases of the VibeIDE test, plus the two properties the owner asked for by name */
class CommunitiesTest {
  /** Every pair of the given nodes linked once */
  private fun clique(nodes: List<Int>): List<WeightedLink> = nodes.flatMapIndexed { i, a ->
    nodes.drop(i + 1).map { b -> WeightedLink(a, b, 1.0) }
  }

  /** A repeatable pseudo-random graph with locality, so there are groups to find */
  private fun seededGraph(size: Int, linkCount: Int, seed: Int = 7): List<WeightedLink> {
    val random = Random(seed)
    return List(linkCount) {
      val a = random.nextInt(size)
      val b = if (random.nextDouble() < 0.85) minOf(size - 1, a + 1 + random.nextInt(4)) else random.nextInt(size)
      WeightedLink(a, b, 1.0)
    }
  }

  private fun groups(partition: IntArray): Map<Int, List<Int>> = partition.indices.groupBy { partition[it] }

  private fun isConnected(members: List<Int>, links: List<WeightedLink>): Boolean {
    val inside = members.toSet()
    val seen = hashSetOf(members.first())
    val queue = ArrayDeque(listOf(members.first()))
    while (queue.isNotEmpty()) {
      val node = queue.removeLast()
      for ((a, b) in links) {
        val other = if (a == node) b else if (b == node) a else continue
        if (other in inside && seen.add(other)) queue.addLast(other)
      }
    }
    return seen.size == inside.size
  }

  @Test
  fun `two dense groups joined by one link split there, lone nodes stay alone, and the result repeats`() {
    val links = clique(listOf(0, 1, 2, 3)) + clique(listOf(4, 5, 6, 7)) + WeightedLink(3, 4, 1.0)
    val partition = Communities.detect(10, links)
    assertEquals(listOf(0, 0, 0, 0, 1, 1, 1, 1, 2, 3), partition.toList())
    assertEquals(partition.toList(), Communities.detect(10, links).toList())
    val together = IntArray(10)
    assertTrue(Communities.modularity(10, links, partition) > Communities.modularity(10, links, together))
  }

  @Test
  fun `every community found is connected, the property Leiden adds over Louvain`() {
    val size = 300
    val links = seededGraph(size, 900)
    val partition = Communities.detect(size, links)
    val found = groups(partition)
    val disconnected = found.values.count { !isConnected(it, links) }
    assertEquals(0, disconnected)
    assertTrue(Communities.modularity(size, links, partition) > 0.3)
    assertTrue(found.size > 3)
  }

  @Test
  fun `every community is connected on many different graphs, not on one lucky seed`() {
    for (seed in 1..12) {
      val size = 120 + seed * 7
      val links = seededGraph(size, size * 3, seed)
      val partition = Communities.detect(size, links)
      for ((community, members) in groups(partition)) {
        assertTrue(isConnected(members, links), "seed $seed: community $community of ${members.size} nodes falls apart")
      }
    }
  }

  @Test
  fun `the result does not depend on the order of the links`() {
    val size = 200
    val links = seededGraph(size, 600)
    val expected = Communities.detect(size, links).toList()
    for (seed in 1..5) {
      assertEquals(expected, Communities.detect(size, links.shuffled(Random(seed))).toList(), "links shuffled with seed $seed")
    }
  }

  @Test
  fun `numbering follows the size, the largest first, equal sizes by their first node`() {
    // Three groups of 5, 3 and 3 joined by single bridges; the two small ones tie and keep the order of their first node
    val links = clique(listOf(0, 1, 2, 3, 4)) + clique(listOf(5, 6, 7)) + clique(listOf(8, 9, 10)) +
      WeightedLink(4, 5, 1.0) + WeightedLink(7, 8, 1.0)
    val partition = Communities.detect(11, links)
    assertEquals(listOf(0, 0, 0, 0, 0, 1, 1, 1, 2, 2, 2), partition.toList())
  }

  @Test
  fun `a group glued on only by weak links does not merge in`() {
    // A guess weighs a quarter of a fact: two dense groups joined by nothing but guesses stay two
    val links = clique(listOf(0, 1, 2, 3)) + clique(listOf(4, 5, 6, 7)) +
      listOf(WeightedLink(1, 5, 0.25), WeightedLink(2, 6, 0.25), WeightedLink(3, 7, 0.25))
    val partition = Communities.detect(8, links)
    assertEquals(listOf(0, 0, 0, 0, 1, 1, 1, 1), partition.toList())
  }

  @Test
  fun `no links means every node is its own community, and nothing means nothing`() {
    assertEquals(listOf(0, 1, 2), Communities.detect(3, emptyList()).toList())
    assertEquals(emptyList(), Communities.detect(0, emptyList()).toList())
    assertEquals(0.0, Communities.modularity(3, emptyList(), intArrayOf(0, 1, 2)))
  }

  @Test
  fun `links off the graph or without weight are ignored, not trusted`() {
    val links = listOf(WeightedLink(0, 1, 1.0), WeightedLink(0, 9, 1.0), WeightedLink(-1, 1, 1.0), WeightedLink(0, 1, 0.0))
    assertEquals(listOf(0, 0), Communities.detect(2, links).toList())
  }

  @Test
  fun `a path of a thousand nodes finishes and stays connected`() {
    // A long chain is the worst shape for aggregation: every level halves it, and 32 levels are plenty
    val size = 1000
    val links = (0 until size - 1).map { WeightedLink(it, it + 1, 1.0) }
    val partition = Communities.detect(size, links)
    for (members in groups(partition).values) assertTrue(isConnected(members, links))
  }
}
