// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.checkpoints

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The checkpoint of a message is the snapshot its own turn took — by time, not by label. */
class RewindPointTest {
  private val first = Checkpoint("a1", "исправь тесты", 1_000)
  private val second = Checkpoint("b2", "исправь тесты", 5_000)
  private val all = listOf(second, first)

  @Test
  fun `the snapshot taken after the message and before the next one`() {
    assertEquals(first, RewindPoint.checkpointFor(all, messageAtMillis = 900, nextMessageAtMillis = 4_000))
    assertEquals(second, RewindPoint.checkpointFor(all, messageAtMillis = 4_000, nextMessageAtMillis = null))
  }

  @Test
  fun `two messages with the same words keep their own snapshots`() {
    assertEquals(second, RewindPoint.checkpointFor(all, messageAtMillis = 4_500, nextMessageAtMillis = 9_000))
  }

  @Test
  fun `a turn that took no snapshot has none, and an older one is not borrowed`() {
    assertNull(RewindPoint.checkpointFor(all, messageAtMillis = 6_000, nextMessageAtMillis = null))
    assertNull(RewindPoint.checkpointFor(all, messageAtMillis = 2_000, nextMessageAtMillis = 4_000))
  }
}
