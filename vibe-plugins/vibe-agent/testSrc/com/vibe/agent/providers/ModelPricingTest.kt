// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelPricingTest {
  // The published Claude Fable 5.1 rates, per million tokens.
  private val fable = ModelPricing(input = 10.0, output = 50.0, cacheRead = 0.25, cacheWrite = 12.5)

  @Test
  fun `a turn is priced from what the provider reported`() {
    val usage = TokenUsage(inputTokens = 100_000, outputTokens = 20_000, cacheReadTokens = 800_000)
    // 1.00 + 1.00 + 0.20
    assertEquals(2.20, fable.costOf(usage)!!, 1e-9)
  }

  @Test
  fun `cache reads are their own rate, not a discount on input`() {
    // Eight hundred thousand cached tokens cost $0.20 here and would cost $8.00 as fresh input:
    // folding them into input would be forty times wrong in the direction that matters.
    val usage = TokenUsage(cacheReadTokens = 800_000)
    assertEquals(0.20, fable.costOf(usage)!!, 1e-9)
    assertEquals(7.80, fable.cacheSavingOf(usage)!!, 1e-9)
  }

  @Test
  fun `an unstated price answers null, not zero`() {
    // «Бесплатно» and «неизвестно» are different answers, and a free-looking turn where the price
    // is merely unknown teaches people to distrust the whole column.
    val usage = TokenUsage(inputTokens = 1000, outputTokens = 1000)
    assertNull(ModelPricing().costOf(usage))
    assertNull(fable.costOf(TokenUsage.NONE))
    assertNull(fable.cacheSavingOf(TokenUsage(inputTokens = 1000)))
  }

  @Test
  fun `a partly filled price is applied as far as it goes`() {
    // Someone who wrote only input and output said what they knew; refusing the whole calculation
    // over a missing cache rate would answer a question they did ask with silence.
    val partial = ModelPricing(input = 3.0, output = 15.0)
    val usage = TokenUsage(inputTokens = 1_000_000, outputTokens = 100_000, cacheReadTokens = 500_000)
    assertEquals(4.50, partial.costOf(usage)!!, 1e-9)
  }

  @Test
  fun `the price survives the file layers`() {
    val base = ProvidersFile.parse(
      """{"providers":[{"id":"p","baseURL":"https://x/v1","models":{"static":[
         {"id":"m","pricing":{"input":10,"output":50,"cacheRead":0.25,"currency":"USD"}}]}}]}""", "global") { }
    val over = ProvidersFile.parse(
      """{"providers":[{"id":"p","models":{"static":[{"id":"m","note":"мой"}]}}]}""", "project") { }
    val model = ProvidersFile.merge(base, over).single().models.single()
    assertEquals(10.0, model.pricing!!.input)
    assertEquals(0.25, model.pricing!!.cacheRead)
    assertEquals("мой", model.note)
  }
}
