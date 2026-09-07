// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.budget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Смета до запуска: считаем по своему журналу, а неизвестное называем, а не выдумываем. */
class RunEstimateTest {
  private fun entry(role: String?, tokens: Long, atMs: Long) =
    SpendLedger.Entry(atMs = atMs, role = role, target = "модель", tokens = tokens)

  private val ledger = listOf(
    entry("planner", 10_000, 1),
    entry("planner", 20_000, 2),
    entry("qa", 4_000, 3),
    entry(null, 999_999, 4), // обычный чат — не роль пайплайна
  )

  @Test
  fun `средний расход роли считается по её же прогонам`() {
    val costs = RunEstimate.roleCosts(ledger)
    assertEquals(15_000, costs["planner"]!!.avgTokens)
    assertEquals(2, costs["planner"]!!.runs)
    assertEquals(4_000, costs["qa"]!!.avgTokens)
    assertFalse(costs.containsKey("chat"), "обычный чат — не роль пайплайна")
  }

  @Test
  fun `неизвестная роль не подменяется средним по чужим`() {
    val estimate = RunEstimate.of(listOf("planner", "security", "qa"), ledger)
    assertEquals(19_000, estimate.tokens, "в сумме только то, что гоняли")
    assertEquals(listOf("security"), estimate.unknownRoles)
    assertEquals(2, estimate.knownSteps)
    assertEquals(3, estimate.totalSteps)
    assertTrue(estimate.partial, "итог заведомо занижен — об этом надо сказать")
  }

  @Test
  fun `цена появляется только когда она известна`() {
    val withPrice = RunEstimate.of(listOf("planner"), ledger, pricePerMillionInput = 10.0, currency = "USD")
    assertEquals(0.15, withPrice.cost!!, 0.0001)
    assertEquals("USD", withPrice.currency)
    val withoutPrice = RunEstimate.of(listOf("planner"), ledger)
    assertNull(withoutPrice.cost)
    assertNull(withoutPrice.currency, "валюта без суммы — выдумка")
  }

  @Test
  fun `берутся последние прогоны, а не все со времён царя Гороха`() {
    val long = (1..20L).map { entry("planner", it * 1_000, it) }
    val estimate = RunEstimate.of(listOf("planner"), long, historyRuns = 2)
    // Два последних: 19 000 и 20 000.
    assertEquals(19_500, estimate.tokens)
  }

  @Test
  fun `пустой журнал даёт честный ноль и список неизвестного`() {
    val estimate = RunEstimate.of(listOf("planner", "qa"), emptyList())
    assertEquals(0, estimate.tokens)
    assertEquals(listOf("planner", "qa"), estimate.unknownRoles)
    assertEquals(0, estimate.knownSteps)
  }
}
