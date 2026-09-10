// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Срок годности цены: протухшая цена продолжает считаться, но обязана быть названной. */
class PriceValidityTest {
  private val today = LocalDate.of(2026, 9, 7)

  private fun model(id: String = "glm-5.3-flash", until: String? = null, priced: Boolean = true) = ModelEntry(
    id = id,
    pricing = if (priced) ModelPricing(input = 0.075, output = 0.25) else null,
    priceValidUntil = until,
  )

  private fun provider(vararg models: ModelEntry) = ProviderEntry(id = "zai", models = models.toList())

  @Test
  fun `день окончания ещё действует`() {
    // Промо GLM-5.3-Flash кончается 09.09 в 24:00 UTC+8 — утром 09-го цена ещё та.
    assertEquals(PriceValidity.State.SOON, PriceValidity.state("2026-09-09", today))
    assertEquals(PriceValidity.State.SOON, PriceValidity.state("2026-09-07", today))
    assertEquals(PriceValidity.State.EXPIRED, PriceValidity.state("2026-09-06", today))
  }

  @Test
  fun `далёкий срок молчит`() {
    assertEquals(PriceValidity.State.NONE, PriceValidity.state("2027-01-01", today))
    assertEquals(PriceValidity.State.NONE, PriceValidity.state(null, today))
    assertEquals(PriceValidity.State.NONE, PriceValidity.state("не дата", today), "мусор — это «не сказано», а не ошибка")
  }

  @Test
  fun `просроченная цена не выбрасывается, а помечается`() {
    assertTrue(PriceValidity.isExpired(model(until = "2026-09-01"), today))
    assertFalse(PriceValidity.isExpired(model(until = "2026-09-09"), today))
    // Считать по устаревшей честнее, чем не считать: порядок величины верный.
    assertTrue(model(until = "2026-09-01").pricing?.stated == true)
  }

  @Test
  fun `срок без цены — описка, а не находка`() {
    val notices = PriceValidity.notices(listOf(provider(model(until = "2026-09-01", priced = false))), today)
    assertTrue(notices.isEmpty(), "предупреждать не о чем: цены нет вовсе")
  }

  @Test
  fun `ближайший срок называется первым`() {
    val notices = PriceValidity.notices(listOf(provider(
      model(id = "далёкая", until = "2026-09-12"),
      model(id = "завтра", until = "2026-09-08"),
      model(id = "вчера", until = "2026-09-06"),
    )), today)
    assertEquals(listOf("вчера", "завтра", "далёкая"), notices.map { it.modelId })
    assertEquals(PriceValidity.State.EXPIRED, notices.first().state)
  }

  @Test
  fun `цена после срока называет множитель подорожания`() {
    val now = ModelPricing(input = 0.075, output = 0.25)
    val after = ModelPricing(input = 0.15, output = 0.50)
    assertEquals(2.0, PriceValidity.inputFactor(now, after)!!, 1e-9)
    // Половины пары мало: сравнивать не с чем, и выдумывать число нельзя.
    assertNull(PriceValidity.inputFactor(now, null))
    assertNull(PriceValidity.inputFactor(null, after))
    // Нулевая цена — это «не сказано», а не «бесплатно»: делить на неё значит соврать.
    assertNull(PriceValidity.inputFactor(ModelPricing(), after))
  }

  @Test
  fun `предупреждение несёт цену после срока`() {
    val model = model(until = "2026-09-09").copy(priceAfter = ModelPricing(input = 0.15, output = 0.50))
    val notice = PriceValidity.notices(listOf(provider(model)), today).single()
    assertEquals(0.15, notice.after!!.input, 1e-9)
  }

  private fun withAfter(until: String?, after: ModelPricing? = ModelPricing(input = 0.15, output = 0.50)) =
    ModelEntry(id = "glm-5.3-flash", pricing = ModelPricing(input = 0.075, output = 0.25),
               priceValidUntil = until, priceAfter = after)

  @Test
  fun `после срока считаем по цене «после», если она записана`() {
    // Повод живой: акция flash кончилась 09.09.2026 в 16:00 UTC, costAfter лежал в записи с самого
    // начала — и на следующий день отчёт считал вдвое дешевле, чем вендор берёт.
    val expired = withAfter("2026-09-06")
    assertEquals(0.15, PriceValidity.effective(expired, today)?.input,
                 "срок прошёл и цена «после» названа — считать надо по ней")
  }

  @Test
  fun `до срока цена не меняется`() {
    assertEquals(0.075, PriceValidity.effective(withAfter("2026-09-09"), today)?.input)
    assertEquals(0.075, PriceValidity.effective(withAfter(null), today)?.input, "срока нет — менять нечего")
  }

  @Test
  fun `без цены «после» поведение прежнее — считаем по устаревшей`() {
    // Здесь довод «считать по устаревшей честнее, чем не считать вовсе» остаётся в силе:
    // новой цены не назвал никто, и выдумывать её мы не станем.
    assertEquals(0.075, PriceValidity.effective(withAfter("2026-09-06", after = null), today)?.input)
    assertEquals(0.075, PriceValidity.effective(withAfter("2026-09-06", after = ModelPricing()), today)?.input,
                 "пустой блок цены ничего не говорит и ценой не считается")
  }
}
