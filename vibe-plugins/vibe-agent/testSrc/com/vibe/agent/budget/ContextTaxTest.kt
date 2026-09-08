// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.budget

import com.vibe.agent.providers.ModelPricing
import com.vibe.agent.providers.TokenUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Контекстный налог: сколько разговор платит за повторную подачу уже сказанного. */
class ContextTaxTest {
  private fun turn(input: Long, output: Long, cacheRead: Long = 0) =
    TokenUsage(inputTokens = input, outputTokens = output, cacheReadTokens = cacheRead)

  @Test
  fun `вход считается вместе с чтением кэша`() {
    val report = ContextTax.of(listOf(turn(10_000, 500, cacheRead = 90_000)))
    assertEquals(10_000, report.inputTokens)
    assertEquals(90_000, report.cacheReadTokens)
    // Перечитанное из кэша дешевле, но это те же токены контекста — налог считает их.
    assertEquals(100_000, report.perTurn)
    assertEquals(200.0, report.ratio!!, 1e-9)
  }

  @Test
  fun `ходы без чисел провайдера пропускаются, а не считаются нулями`() {
    val report = ContextTax.of(listOf(turn(1_000, 100), TokenUsage.NONE, turn(3_000, 100)))
    assertEquals(2, report.turns)
    assertEquals(4_000, report.inputTokens)
    assertEquals(2_000, report.perTurn)
  }

  @Test
  fun `без выхода делить не на что`() {
    assertNull(ContextTax.of(listOf(turn(1_000, 0))).ratio)
    assertEquals(0, ContextTax.of(emptyList()).perTurn)
  }

  @Test
  fun `деньги считаются только по названной цене`() {
    val usages = listOf(turn(1_000_000, 100_000, cacheRead = 1_000_000))
    assertNull(ContextTax.of(usages).inputCost)
    val priced = ContextTax.of(usages, ModelPricing(input = 10.0, output = 50.0, cacheRead = 0.25))
    // $10 за миллион входных + $0.25 за миллион из кэша против $5 за выход.
    assertEquals(10.25, priced.inputCost!!, 1e-9)
    assertEquals(5.0, priced.outputCost!!, 1e-9)
  }
}
