// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.budget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The task as a unit: all steps of one pipeline run summed into one bill. */
class SpendRunTest {
  private fun entry(run: String?, tokens: Long, cost: Double?) =
    SpendLedger.Entry(1L, "backend-dev", "acp/claude", tokens, cost, cost?.let { "USD" }, runId = run)

  @Test
  fun `a run bill sums its steps and nothing else`() {
    val line = SpendLedger.ofRun(listOf(entry("r1", 100, 0.10), entry("r1", 50, 0.05), entry("r2", 999, 9.0), entry(null, 7, 0.01)), "r1")!!
    assertEquals(150, line.tokens)
    assertEquals(0.15, line.cost, 1e-9)
  }

  @Test
  fun `an unknown run has no bill`() {
    assertNull(SpendLedger.ofRun(listOf(entry("r1", 100, 0.1)), "r9"))
  }
}
