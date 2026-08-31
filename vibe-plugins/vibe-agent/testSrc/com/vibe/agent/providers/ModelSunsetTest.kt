// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelSunsetTest {
  private val today = LocalDate.of(2026, 8, 31)

  @Test
  fun `без даты правило молчит`() {
    assertEquals(ModelSunset.State.NONE, ModelSunset.state(null, today))
    assertEquals(ModelSunset.State.NONE, ModelSunset.state("   ", today))
    assertNull(ModelSunset.parse("не дата"))
    assertEquals(ModelSunset.State.NONE, ModelSunset.state("не дата", today), "мусор — это «не сказано», а не ошибка")
  }

  @Test
  fun `далёкая дата ещё не повод шуметь`() {
    assertEquals(ModelSunset.State.NONE, ModelSunset.state("2026-11-12", today))
    assertEquals(73L, ModelSunset.daysLeft("2026-11-12", today))
  }

  @Test
  fun `за месяц до даты начинается предупреждение`() {
    assertEquals(ModelSunset.State.SOON, ModelSunset.state("2026-09-20", today))
    assertEquals(ModelSunset.State.SOON, ModelSunset.state(today.plusDays(ModelSunset.WARN_DAYS).toString(), today))
    assertEquals(ModelSunset.State.NONE, ModelSunset.state(today.plusDays(ModelSunset.WARN_DAYS + 1).toString(), today))
  }

  @Test
  fun `сам день отключения ещё рабочий`() {
    // Доступ кончается В КОНЦЕ дня; отобрать модель с утра — отнять день работы просто так.
    assertEquals(ModelSunset.State.SOON, ModelSunset.state(today.toString(), today))
    assertEquals(ModelSunset.State.RETIRED, ModelSunset.state(today.minusDays(1).toString(), today))
  }

  @Test
  fun `снятая модель не предлагается, но остаётся в файле`() {
    val retired = ModelEntry(id = "gpt-old", sunsetDate = "2026-08-01")
    val live = ModelEntry(id = "gpt-new", sunsetDate = "2027-01-01")
    assertTrue(ModelSunset.isRetired(retired, today))
    assertFalse(ModelSunset.isRetired(live, today))
  }

  @Test
  fun `сводка сортируется по близости даты`() {
    val provider = ProviderEntry(
      id = "openai",
      models = listOf(
        ModelEntry(id = "far", sunsetDate = "2026-09-25"),
        ModelEntry(id = "near", sunsetDate = "2026-09-02"),
        ModelEntry(id = "silent"),
      ),
    )
    val notices = ModelSunset.notices(listOf(provider), today)
    assertEquals(listOf("near", "far"), notices.map { it.modelId }, "первым говорим о том, что уходит раньше")
    assertTrue(notices.all { it.providerId == "openai" })
  }
}
