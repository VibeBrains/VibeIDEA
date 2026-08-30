// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.budget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpendLedgerTest {
  private fun entry(atMs: Long, role: String?, tokens: Long, cost: Double? = null, currency: String? = null) =
    SpendLedger.Entry(atMs, role, "acp/claude", tokens, cost, currency)

  @Test
  fun `entries outside the window are not counted`() {
    val now = 10 * SpendLedger.DAY_MS
    val entries = listOf(entry(now - 2 * SpendLedger.DAY_MS, "qa", 100), entry(now - 1000, "qa", 50))
    assertEquals(1, SpendLedger.within(entries, now, SpendLedger.DAY_MS).size)
  }

  @Test
  fun `tokens are summed per role`() {
    val entries = listOf(entry(0, "qa", 100), entry(0, "qa", 50), entry(0, "security", 10))
    assertEquals(150, SpendLedger.tokensOf(entries, "qa"))
    assertEquals(10, SpendLedger.tokensOf(entries, "security"))
  }

  @Test
  fun `an ordinary chat is its own line, not a nameless role`() {
    val lines = SpendLedger.byRole(listOf(entry(0, null, 100), entry(0, "qa", 10)))
    assertEquals(SpendLedger.CHAT, lines.first().name)
  }

  @Test
  fun `the report is sorted by spend, not by name`() {
    // Отчёт, отсортированный по алфавиту, прячет ответ на вопрос «куда ушли деньги».
    val lines = SpendLedger.byRole(listOf(entry(0, "aaa", 1), entry(0, "zzz", 900)))
    assertEquals("zzz", lines.first().name)
  }

  @Test
  fun `money is summed only where the provider reported it`() {
    val lines = SpendLedger.byRole(listOf(entry(0, "qa", 10, 0.5, "USD"), entry(0, "qa", 10)))
    assertEquals(0.5, lines.single().cost)
    assertEquals("USD", lines.single().currency)
  }

  @Test
  fun `mixed currencies are not silently added together`() {
    // Сумма долларов с рублями — это не число, а вранье; в отчёте валюта тогда не называется.
    val lines = SpendLedger.byRole(listOf(entry(0, "qa", 10, 1.0, "USD"), entry(0, "qa", 10, 1.0, "RUB")))
    assertNull(lines.single().currency)
  }

  @Test
  fun `the store keeps only its capacity`() {
    val store = SpendLedger.Store(capacity = 3)
    repeat(10) { store.add(entry(it.toLong(), "qa", 1)) }
    assertEquals(3, store.snapshot().size)
    assertEquals(9, store.snapshot().last().atMs)
  }
}

class RoleBudgetTest {
  @Test
  fun `a zero limit is off, not a wall`() {
    assertEquals(RoleBudget.Verdict.OK, RoleBudget.check(spentTokens = 10_000_000, limitTokens = 0).verdict)
  }

  @Test
  fun `the warning comes while there is still room`() {
    assertEquals(RoleBudget.Verdict.OK, RoleBudget.check(79, 100).verdict)
    assertEquals(RoleBudget.Verdict.WARN, RoleBudget.check(80, 100).verdict)
  }

  @Test
  fun `reaching the limit exceeds it`() {
    assertEquals(RoleBudget.Verdict.EXCEEDED, RoleBudget.check(100, 100).verdict)
    assertEquals(RoleBudget.Verdict.EXCEEDED, RoleBudget.check(101, 100).verdict)
  }

  @Test
  fun `the status carries the numbers the message needs`() {
    val status = RoleBudget.check(50, 200)
    assertEquals(25, status.percent)
    assertEquals(50, status.spent)
    assertEquals(200, status.limit)
    assertTrue(status.verdict == RoleBudget.Verdict.OK)
  }
}
