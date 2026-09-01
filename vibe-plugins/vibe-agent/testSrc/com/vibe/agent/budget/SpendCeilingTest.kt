// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.budget

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpendCeilingTest {
  private val now = 1_700_000_000_000L

  private fun entry(agoMs: Long, cost: Double?) =
    SpendLedger.Entry(atMs = now - agoMs, role = null, target = "llm:x/y", tokens = 1000, costAmount = cost,
                      costCurrency = cost?.let { "USD" })

  private val plan = SpendCeiling.Limits(fiveHours = 12.0, week = 30.0, month = 60.0)

  @Test
  fun `nothing is watched until a ceiling is set`() {
    // A ceiling nobody asked for is a wall in the middle of real work.
    val off = SpendCeiling.Limits()
    assertTrue(!off.any)
    assertNull(SpendCeiling.blocking(listOf(entry(0, 999.0)), now, off))
  }

  @Test
  fun `the short window stops the turn while the month still has room`() {
    // $13 in the last hour: the five-hour window is gone, the month is barely touched. Watching
    // only the longest window would let someone burn the short one in twenty minutes and learn
    // about it from the provider.
    val spent = listOf(entry(30 * 60_000, 6.5), entry(45 * 60_000, 6.5))
    val blocked = SpendCeiling.blocking(spent, now, plan)
    assertNotNull(blocked)
    assertEquals(SpendCeiling.FIVE_HOURS, blocked.window.id)
    assertEquals(13.0, blocked.spent)
  }

  @Test
  fun `spending falls out of the window as it ages`() {
    // The same $13, six hours ago: the five-hour window is empty again, exactly as the plan works.
    val old = listOf(entry(6 * 60 * 60_000, 13.0))
    assertNull(SpendCeiling.blocking(old, now, plan))
    assertEquals(0.0, SpendCeiling.spentIn(old, now, SpendCeiling.FIVE_HOURS_MS))
    assertEquals(13.0, SpendCeiling.spentIn(old, now, SpendCeiling.WEEK_MS))
  }

  @Test
  fun `a warning comes while there is still room to act`() {
    // At the very edge a warning is useless: the next turn is already the refused one.
    val near = listOf(entry(60_000, 10.0))   // 10 of 12 = 83%
    assertNull(SpendCeiling.blocking(near, now, plan))
    val warned = SpendCeiling.warning(near, now, plan)
    assertNotNull(warned)
    assertEquals(SpendCeiling.FIVE_HOURS, warned.window.id)
    assertEquals(2.0, warned.left)
    // Half spent is not news.
    assertNull(SpendCeiling.warning(listOf(entry(60_000, 6.0)), now, plan))
  }

  @Test
  fun `the fullest window is reported first`() {
    // A person stopped by one window still needs to know which other one is nearly gone.
    val mixed = listOf(entry(60_000, 9.0), entry(3 * 24 * 60 * 60_000, 20.0))
    val all = SpendCeiling.check(mixed, now, plan)
    // $29 of the weekly $30 (97 %) outranks $9 of the five-hour $12 (75 %): fullest, not shortest.
    assertEquals(SpendCeiling.WEEK, all.first().window.id)
    assertEquals(SpendCeiling.FIVE_HOURS, all[1].window.id)
    assertEquals(SpendCeiling.MONTH, all[2].window.id)             // 29/60 = 48 %
  }

  @Test
  fun `turns the provider did not price count as zero, not as unknown`() {
    // We never guess a price from a table of our own: it is out of date the day a vendor changes
    // one, and a guessed ceiling stops work for a reason that was invented here.
    val free = listOf(entry(60_000, null), entry(60_000, null))
    assertEquals(0.0, SpendCeiling.spentIn(free, now, SpendCeiling.FIVE_HOURS_MS))
    assertNull(SpendCeiling.blocking(free, now, plan))
  }
}
