// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.budget

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpendThreadTest {
  private fun entry(thread: String?, tokens: Long, cost: Double? = null) =
    SpendLedger.Entry(1L, role = null, target = "llm:x/y", tokens = tokens,
                      costAmount = cost, costCurrency = cost?.let { "USD" }, threadId = thread)

  @Test
  fun `one conversation is priced apart from the day around it`() {
    // «Во что мне обошёлся ЭТОТ чат» is the question people ask; a day's total answers a
    // different one and looks like an overcharge.
    val entries = listOf(
      entry("t1", 1000, 0.10), entry("t2", 5000, 0.90), entry("t1", 2000, 0.20))
    val line = SpendLedger.ofThread(entries, "t1")!!
    assertEquals(3000, line.tokens)
    assertEquals(0.30, line.cost, 1e-9)
    assertEquals(2, line.runs)
  }

  @Test
  fun `a conversation with nothing recorded answers nothing, not zero`() {
    // Zero would read as «этот чат был бесплатным», which is a claim we cannot make.
    assertNull(SpendLedger.ofThread(listOf(entry("t1", 100)), "t2"))
  }

  @Test
  fun `entries written before threads were recorded belong to no chat`() {
    // An old ledger stays readable; it simply answers one question fewer.
    val old = entry(null, 4000, 0.50)
    assertNull(SpendLedger.ofThread(listOf(old), "t1"))
    assertEquals(4000, SpendLedger.byTarget(listOf(old)).single().tokens)
  }
}
