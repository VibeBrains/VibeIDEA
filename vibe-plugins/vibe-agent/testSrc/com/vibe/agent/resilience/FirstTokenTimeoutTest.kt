// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.resilience

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The first-fragment wait grows with what makes a model slow, and never past the point of pretending.
 *
 * The numbers below are the ones a person sees as the "model did not answer" error, so each of them is pinned:
 * a wait that silently stayed at 30 s is exactly the bug this module exists to prevent.
 */
class FirstTokenTimeoutTest {
  @Test
  fun `an unknown model gets the setting, not less than the default`() {
    assertEquals(30_000L, FirstTokenTimeout.limit(30_000, null, emptyList(), retry = 0))
    // A zero or a tiny setting would mean «do not wait», which no model survives.
    assertEquals(30_000L, FirstTokenTimeout.limit(0, null, emptyList(), retry = 0))
    assertEquals(30_000L, FirstTokenTimeout.limit(5_000, null, emptyList(), retry = 0))
  }

  @Test
  fun `a person who asked for more waits more`() {
    assertEquals(60_000L, FirstTokenTimeout.limit(60_000, null, emptyList(), retry = 0))
  }

  @Test
  fun `the slowest recent start of the model lifts the floor one and a half times`() {
    // Kimi K3.1 Pro on a 400-message thread: 40 s to the first fragment.
    assertEquals(60_000L, FirstTokenTimeout.limit(30_000, null, listOf(12_000, 40_000), retry = 0))
  }

  @Test
  fun `a long prompt adds ten seconds per started fifty thousand tokens`() {
    assertEquals(0L, FirstTokenTimeout.promptExtra(50_000))
    assertEquals(10_000L, FirstTokenTimeout.promptExtra(50_001))
    assertEquals(10_000L, FirstTokenTimeout.promptExtra(100_000))
    assertEquals(20_000L, FirstTokenTimeout.promptExtra(100_001))
    // 200k tokens is 150k past the free step: three started 50k steps, so 30 s over the 30 s floor.
    assertEquals(60_000L, FirstTokenTimeout.limit(30_000, 200_000, emptyList(), retry = 0))
  }

  @Test
  fun `every repeat multiplies the floor, so the same wait is not walked into twice`() {
    assertEquals(30_000L, FirstTokenTimeout.limit(30_000, null, emptyList(), retry = 0))
    assertEquals(60_000L, FirstTokenTimeout.limit(30_000, null, emptyList(), retry = 1))
    assertEquals(90_000L, FirstTokenTimeout.limit(30_000, null, emptyList(), retry = 2))
  }

  @Test
  fun `the hard ceiling caps everything`() {
    assertEquals(FirstTokenTimeout.HARD_CEILING_MS, FirstTokenTimeout.limit(200_000, 1_000_000, emptyList(), retry = 3))
    assertEquals(FirstTokenTimeout.HARD_CEILING_MS, FirstTokenTimeout.limit(300_000, null, listOf(400_000), retry = 0))
  }

  @Test
  fun `the history keeps only the recent delays of one model`() {
    val history = FirstTokenTimeout.History(size = 3)
    repeat(5) { i -> history.note("kimi", (i + 1) * 1_000L) }
    history.note("qwen", 7_000)
    assertEquals(listOf(3_000L, 4_000L, 5_000L), history.recent("kimi"))
    assertEquals(listOf(7_000L), history.recent("qwen"))
    assertEquals(emptyList(), history.recent("nobody"))
  }

  @Test
  fun `a first-fragment refusal is a timeout to the retry policy, so the attempt is repeated`() {
    val refusal = FirstTokenTimeoutException(waitedMs = 40_000, limitMs = 30_000, message = "no fragment")
    assertTrue(refusal is java.net.SocketTimeoutException)
    assertEquals(RetryPolicy.Kind.TRANSIENT, RetryPolicy.classify(null, refusal))
    assertTrue(RetryPolicy.shouldRetry(RetryPolicy.Kind.TRANSIENT, attempt = 1))
  }
}
