// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.budget

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpendForecastTest {
  private val now = 1_700_000_000_000L
  private val plan = SpendCeiling.Limits(fiveHours = 12.0)

  private fun entry(agoMs: Long, cost: Double) =
    SpendLedger.Entry(atMs = now - agoMs, role = null, target = "llm:x/y", tokens = 100, costAmount = cost, costCurrency = "USD")

  @Test
  fun `the forecast answers when, not how much`() {
    // $6 in the last hour, $6 left of the five-hour window: about an hour to go.
    val spent = listOf(entry(30 * 60_000, 3.0), entry(50 * 60_000, 3.0))
    val verdict = SpendCeiling.check(spent, now, plan).single()
    val left = SpendCeiling.timeToLimitMs(spent, now, verdict)
    assertNotNull(left)
    assertEquals(60, left / 60_000, "около часа, а вышло минут: " + left / 60_000)
  }

  @Test
  fun `a burst is noticed, not averaged away`() {
    // The same $6, but spent in the last ten minutes: the pace window is an hour, so the forecast
    // is the same — what changes is that money spent five hours ago does NOT slow it down.
    val burst = listOf(entry(5 * 60_000, 6.0), entry(4 * 60 * 60_000, 3.0))
    val verdict = SpendCeiling.check(burst, now, plan).single()
    val left = SpendCeiling.timeToLimitMs(burst, now, verdict)
    assertNotNull(left)
    // $3 left of $12 at $6/hour → half an hour.
    assertEquals(30, left / 60_000)
  }

  @Test
  fun `no recent spending means no forecast, not an infinite one`() {
    // A forecast of «никогда» reads as reassurance, and it would be an invention.
    val idle = listOf(entry(3 * 60 * 60_000, 5.0))
    val verdict = SpendCeiling.check(idle, now, plan).single()
    assertNull(SpendCeiling.timeToLimitMs(idle, now, verdict))
  }

  @Test
  fun `an exhausted window has nothing left to forecast`() {
    val gone = listOf(entry(60_000, 13.0))
    val verdict = SpendCeiling.check(gone, now, plan).single()
    assertTrue(verdict.exceeded)
    assertNull(SpendCeiling.timeToLimitMs(gone, now, verdict))
  }
}
