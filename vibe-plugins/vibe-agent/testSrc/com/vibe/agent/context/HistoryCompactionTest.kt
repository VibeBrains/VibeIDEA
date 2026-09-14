// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryCompactionTest {
  @Test
  fun `nothing folds below the trigger`() {
    assertEquals(0, HistoryCompaction.foldCount(List(10) { 100L }, window = 2000))
  }

  @Test
  fun `crossing the trigger folds the oldest down to the target`() {
    // 20 × 100 = 2000 of a 2400 window is 83 % — past the trigger; the target is 1200.
    val cut = HistoryCompaction.foldCount(List(20) { 100L }, window = 2400)
    assertEquals(8, cut)
  }

  @Test
  fun `the recent exchange is never folded`() {
    val cut = HistoryCompaction.foldCount(List(8) { 1000L }, window = 1000)
    assertEquals(8 - HistoryCompaction.KEEP_RECENT, cut)
  }

  @Test
  fun `an earlier fold only moves forward`() {
    assertEquals(5, HistoryCompaction.foldCount(List(10) { 10L }, window = 100_000, alreadyFolded = 5))
  }

  @Test
  fun `without a known window nothing new is folded`() {
    assertEquals(0, HistoryCompaction.foldCount(List(50) { 10_000L }, window = null))
  }

  @Test
  fun `tool schemas count towards the trigger`() {
    val estimates = List(10) { 100L }
    assertEquals(0, HistoryCompaction.foldCount(estimates, window = 2000))
    assertTrue(HistoryCompaction.foldCount(estimates, window = 2000, fixedTokens = 800) > 0, "1000 of messages + 800 of schemas is past 80 % of 2000")
  }

  @Test
  fun `a custom policy moves the trigger and keeps its own tail`() {
    val estimates = List(20) { 100L }
    val policy = HistoryCompaction.Policy(triggerPercent = 50, targetPercent = 30, keepRecent = 10)
    assertEquals(0, HistoryCompaction.foldCount(estimates, window = 5000, policy = policy))
    assertEquals(10, HistoryCompaction.foldCount(estimates, window = 3000, policy = policy))
  }

  @Test
  fun `a target at or above the trigger is kept below it`() {
    assertEquals(59, HistoryCompaction.Policy(triggerPercent = 60, targetPercent = 80).effectiveTarget)
  }
}
