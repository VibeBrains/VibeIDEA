// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
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
    assertEquals(2.20, fable.costOf(usage, null)!!, 1e-9)
  }

  @Test
  fun `cache reads are their own rate, not a discount on input`() {
    // Eight hundred thousand cached tokens cost $0.20 here and would cost $8.00 as fresh input:
    // folding them into input would be forty times wrong in the direction that matters.
    val usage = TokenUsage(cacheReadTokens = 800_000)
    assertEquals(0.20, fable.costOf(usage, null)!!, 1e-9)
    assertEquals(7.80, fable.cacheSavingOf(usage, null)!!, 1e-9)
  }

  @Test
  fun `an unstated price answers null, not zero`() {
    // «Бесплатно» and «неизвестно» are different answers, and a free-looking turn where the price
    // is merely unknown teaches people to distrust the whole column.
    val usage = TokenUsage(inputTokens = 1000, outputTokens = 1000)
    assertNull(ModelPricing().costOf(usage, null))
    assertNull(fable.costOf(TokenUsage.NONE, null))
    assertNull(fable.cacheSavingOf(TokenUsage(inputTokens = 1000), null))
  }

  @Test
  fun `a partly filled price is applied as far as it goes`() {
    // Someone who wrote only input and output said what they knew; refusing the whole calculation
    // over a missing cache rate would answer a question they did ask with silence.
    val partial = ModelPricing(input = 3.0, output = 15.0)
    val usage = TokenUsage(inputTokens = 1_000_000, outputTokens = 100_000, cacheReadTokens = 500_000)
    assertEquals(4.50, partial.costOf(usage, null)!!, 1e-9)
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
    assertEquals(6.075, p.costOf(long, null)!!, 1e-9)
  }

  @Test
  fun `под порогом цена обычная`() {
    val p = ModelPricing(input = 10.0, output = 50.0,
                         longContext = ModelPricing.LongContext(272_000, input = 2.0, output = 1.5))
    val short = TokenUsage(inputTokens = 100_000, outputTokens = 1_000)
    // Без надбавки: 100000*10/1M + 1000*50/1M = 1.0 + 0.05
    assertEquals(1.05, p.costOf(short, null)!!, 1e-9)
  }

  @Test
  fun `порог считается по промпту, а кэш входит в него`() {
    // Вендор говорит «prompts with more than N input tokens»; кэшированная часть промпта тоже
    // отправлена, поэтому в порог входит. Выход — нет: его в момент назначения цены ещё нет.
    val p = ModelPricing(input = 10.0, output = 50.0, cacheRead = 1.0,
                         longContext = ModelPricing.LongContext(272_000, input = 2.0, cache = 2.0, output = 1.5))
    val split = TokenUsage(inputTokens = 200_000, cacheReadTokens = 100_000)
    assertTrue(p.longContextApplies(split), "200K свежих плюс 100K из кэша — это промпт длиннее порога")
    val written = TokenUsage(inputTokens = 200_000, cacheWriteTokens = 100_000)
    assertTrue(p.longContextApplies(written), "запись в кэш — тоже часть промпта: её делает как раз первый длинный запрос")
    val outputOnly = TokenUsage(inputTokens = 10_000, outputTokens = 500_000)
    assertFalse(p.longContextApplies(outputOnly), "длинный ответ порога не поднимает")
  }

  @Test
  fun `объявление без множителей надбавкой не считается`() {
    val p = ModelPricing(input = 10.0, longContext = ModelPricing.LongContext(272_000))
    assertFalse(p.longContextApplies(TokenUsage(inputTokens = 500_000)), "все множители по единице — надбавки нет")
  }

  // DeepSeek V4.1 Flash, peak rates: 01:00–04:00 and 06:00–10:00 UTC on weekdays, half the rate at
  // any other time (api-docs.deepseek.com/quick_start/pricing, checked 11.09.2026).
  private val flash = ModelPricing(
    input = 0.30, output = 1.20, cacheRead = 0.006,
    timeOfDay = ModelPricing.TimeOfDay(
      peakWindows = listOf(ModelPricing.TimeOfDay.Window(60, 240), ModelPricing.TimeOfDay.Window(360, 600)),
      peakDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
      offPeakFactor = 0.5,
    ),
  )
  private val million = TokenUsage(inputTokens = 1_000_000)

  @Test
  fun `в пик считается по объявленной ставке`() {
    // Четверг, 02:30 UTC — внутри окна 01:00–04:00.
    assertEquals(0.30, flash.costOf(million, Instant.parse("2026-09-10T02:30:00Z"))!!, 1e-9)
    assertEquals(0.30, flash.costOf(million, Instant.parse("2026-09-10T09:59:00Z"))!!, 1e-9)
  }

  @Test
  fun `вне пика все ставки умножаются на множитель`() {
    assertEquals(0.15, flash.costOf(million, Instant.parse("2026-09-10T12:00:00Z"))!!, 1e-9, "будний полдень")
    assertEquals(0.15, flash.costOf(million, Instant.parse("2026-09-12T02:30:00Z"))!!, 1e-9, "суббота — пика нет")
    assertEquals(0.15, flash.costOf(million, Instant.parse("2026-09-10T04:00:00Z"))!!, 1e-9, "конец окна не входит")
  }

  @Test
  fun `время хода неизвестно — считаем по пиковой`() {
    // Завышение безопаснее для потолка расходов, чем занижение, а выдумывать долю часов не с чего.
    assertEquals(0.30, flash.costOf(million, null)!!, 1e-9)
  }

  @Test
  fun `окно через полночь`() {
    val night = ModelPricing(
      input = 1.0,
      timeOfDay = ModelPricing.TimeOfDay(listOf(ModelPricing.TimeOfDay.Window(22 * 60, 2 * 60)), offPeakFactor = 0.5),
    )
    assertEquals(1.0, night.costOf(million, Instant.parse("2026-09-10T23:30:00Z"))!!, 1e-9)
    assertEquals(1.0, night.costOf(million, Instant.parse("2026-09-11T01:00:00Z"))!!, 1e-9)
    assertEquals(0.5, night.costOf(million, Instant.parse("2026-09-11T03:00:00Z"))!!, 1e-9)
  }

  @Test
  fun `экономия кэша считается по тому же часу`() {
    // (0.30 − 0.006) × 0.5 вне пика: экономия по пиковой ставке назвала бы число, которого нет в счёте.
    val usage = TokenUsage(cacheReadTokens = 1_000_000)
    assertEquals(0.147, flash.cacheSavingOf(usage, Instant.parse("2026-09-10T12:00:00Z"))!!, 1e-9)
  }

  @Test
  fun `расписание читается из файла, битое называется вслух`() {
    val warnings = mutableListOf<String>()
    val parsed = ProvidersFile.parse(
      """{"providers":[{"id":"deepseek","baseURL":"https://x","models":{"static":[
         {"id":"deepseek-flash","cost":{"input":0.30,"timeOfDay":{"peakUtc":["01:00-04:00","22:00-24:00"],
          "peakDays":["mon","tue","wed","thu","fri"],"offPeakFactor":0.5}}},
         {"id":"broken","cost":{"input":1,"timeOfDay":{"peakUtc":["25:00-04:00"],"offPeakFactor":0.5}}}]}}]}""",
      "test") { warnings.add(it) }
    val models = parsed.single().models
    val schedule = models.first { it.id == "deepseek-flash" }.pricing!!.timeOfDay!!
    assertEquals(listOf(ModelPricing.TimeOfDay.Window(60, 240), ModelPricing.TimeOfDay.Window(22 * 60, 0)),
                 schedule.peakWindows, "«24:00» — конец суток")
    assertEquals(5, schedule.peakDays.size)
    assertNull(models.first { it.id == "broken" }.pricing!!.timeOfDay, "битое окно роняет весь блок")
    assertEquals(1, warnings.size, "и об этом сказано вслух: $warnings")
  }
}
