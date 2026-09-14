// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

import com.vibe.agent.providers.ModelPricing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.DayOfWeek
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `offPeak` on a pipeline step: VibeIDE's field, and the moment the step may start. */
class OffPeakStepTest {
  // DeepSeek: peak 01:00–04:00 and 06:00–10:00 UTC on weekdays (api-docs.deepseek.com/quick_start/pricing).
  private val deepseek = ModelPricing.TimeOfDay(
    peakWindows = listOf(ModelPricing.TimeOfDay.Window(60, 240), ModelPricing.TimeOfDay.Window(360, 600)),
    peakDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
    offPeakFactor = 0.5,
  )

  private fun step(json: String) = PipelinesFile.parseStep(Json.parseToJsonElement(json).jsonObject, emptyMap()) {}

  @Test
  fun `off-peak now means start now`() {
    val monday0030 = Instant.parse("2026-09-14T00:30:00Z")
    assertEquals(monday0030, deepseek.nextOffPeak(monday0030))
  }

  @Test
  fun `inside a peak the step starts at the first off-peak minute`() {
    assertEquals(Instant.parse("2026-09-14T04:00:00Z"), deepseek.nextOffPeak(Instant.parse("2026-09-14T02:17:45Z")))
    assertEquals(Instant.parse("2026-09-14T10:00:00Z"), deepseek.nextOffPeak(Instant.parse("2026-09-14T09:59:30Z")))
  }

  @Test
  fun `a schedule with no off-peak minute in a week has no moment`() {
    val always = ModelPricing.TimeOfDay(listOf(ModelPricing.TimeOfDay.Window(0, 1439), ModelPricing.TimeOfDay.Window(1439, 0)), offPeakFactor = 0.5)
    assertNull(always.nextOffPeak(Instant.parse("2026-09-14T12:00:00Z")))
  }

  @Test
  fun `offPeak with the step's own model is read`() {
    assertTrue(step("""{"role":"code-reviewer","task":"t","model":"deepseek/deepseek-flash","offPeak":true}""").offPeak)
    assertFalse(step("""{"role":"code-reviewer","task":"t","model":"deepseek/deepseek-flash"}""").offPeak)
  }

  @Test
  fun `offPeak without the step's own model is refused, even when its role has one`() {
    assertFailsWith<IllegalArgumentException> { step("""{"role":"code-reviewer","task":"t","offPeak":true}""") }
    val roleModels = mapOf("code-reviewer" to ("deepseek" to "deepseek-flash"))
    assertFailsWith<IllegalArgumentException> {
      PipelinesFile.parseStep(Json.parseToJsonElement("""{"role":"code-reviewer","task":"t","offPeak":true}""").jsonObject, roleModels) {}
    }
  }
}
