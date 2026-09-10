// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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

  @Test
  fun `надбавка за длинный промпт действует на весь запрос`() {
    // GPT-6 Astra: «more than 272K input tokens are priced at 2x input and cache rates and 1.5x
    // output FOR THE FULL REQUEST» — не за превышение, а за всё (developers.openai.com, 10.09.2026).
    val p = ModelPricing(input = 10.0, output = 50.0, cacheRead = 1.0,
                         longContext = ModelPricing.LongContext(272_000, input = 2.0, cache = 2.0, output = 1.5))
    val long = TokenUsage(inputTokens = 300_000, outputTokens = 1_000)
    // 300000*10*2/1M + 1000*50*1.5/1M = 6.0 + 0.075
    assertEquals(6.075, p.costOf(long)!!, 1e-9)
  }

  @Test
  fun `под порогом цена обычная`() {
    val p = ModelPricing(input = 10.0, output = 50.0,
                         longContext = ModelPricing.LongContext(272_000, input = 2.0, output = 1.5))
    val short = TokenUsage(inputTokens = 100_000, outputTokens = 1_000)
    // Без надбавки: 100000*10/1M + 1000*50/1M = 1.0 + 0.05
    assertEquals(1.05, p.costOf(short)!!, 1e-9)
  }

  @Test
  fun `порог считается по промпту, а кэш входит в него`() {
    // Вендор говорит «prompts with more than N input tokens»; кэшированная часть промпта тоже
    // отправлена, поэтому в порог входит. Выход — нет: его в момент назначения цены ещё нет.
    val p = ModelPricing(input = 10.0, output = 50.0, cacheRead = 1.0,
                         longContext = ModelPricing.LongContext(272_000, input = 2.0, cache = 2.0, output = 1.5))
    val split = TokenUsage(inputTokens = 200_000, cacheReadTokens = 100_000)
    assertTrue(p.longContextApplies(split), "200K свежих плюс 100K из кэша — это промпт длиннее порога")
    val outputOnly = TokenUsage(inputTokens = 10_000, outputTokens = 500_000)
    assertFalse(p.longContextApplies(outputOnly), "длинный ответ порога не поднимает")
  }

  @Test
  fun `объявление без множителей надбавкой не считается`() {
    val p = ModelPricing(input = 10.0, longContext = ModelPricing.LongContext(272_000))
    assertFalse(p.longContextApplies(TokenUsage(inputTokens = 500_000)), "все множители по единице — надбавки нет")
  }
}
