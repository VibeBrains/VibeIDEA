// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.background

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskLimitsTest {
  private val start = 1_000_000L
  private val limits = TaskLimits.Limits(ttlMs = 60_000, pollIntervalMs = 10_000)

  @Test
  fun `a job ends and says so instead of hanging forever`() {
    // A job with no declared lifetime is indistinguishable from a hung one.
    assertFalse(TaskLimits.expired(start, start + 59_000, limits))
    assertTrue(TaskLimits.expired(start, start + 60_000, limits))
  }

  @Test
  fun `the heartbeat is counted from the last word, not from the start`() {
    // Counting from the start produces a burst of overdue notices instead of a steady pulse.
    assertFalse(TaskLimits.progressDue(lastReportMs = start + 55_000, nowMs = start + 60_000, limits))
    assertTrue(TaskLimits.progressDue(lastReportMs = start + 50_000, nowMs = start + 60_000, limits))
  }

  @Test
  fun `zero lifetime means unlimited, and that is an explicit choice`() {
    val forever = TaskLimits.Limits(ttlMs = 0)
    assertTrue(forever.unlimited)
    assertFalse(TaskLimits.expired(start, start + 10L * 60 * 60 * 1000, forever))
    assertNull(TaskLimits.remainingSeconds(start, start + 1000, forever))
  }

  @Test
  fun `a job past its deadline has zero left, never a negative number`() {
    assertEquals(30, TaskLimits.remainingSeconds(start, start + 30_000, limits))
    assertEquals(0, TaskLimits.remainingSeconds(start, start + 90_000, limits))
  }

  @Test
  fun `the defaults are longer than a check and shorter than a night`() {
    val d = TaskLimits.Limits()
    assertEquals(30, d.ttlMs / 60_000)
    assertEquals(60, d.pollIntervalMs / 1000)
  }
}
