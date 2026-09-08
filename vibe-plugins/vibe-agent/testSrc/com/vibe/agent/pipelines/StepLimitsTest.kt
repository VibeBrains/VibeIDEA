// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Потолки шага: поля, которые файл принимал с самого начала и не применял ни разу. */
class StepLimitsTest {
  private fun step(maxTokens: Int? = null, maxSteps: Int? = null) =
    PipelineStep(role = "qa", task = "проверь", maxTokens = maxTokens, maxSteps = maxSteps)

  @Test
  fun `без потолков не срабатывает никогда`() {
    assertEquals(StepLimits.Verdict.OK, StepLimits.check(1_000_000, 500, null, null))
    assertFalse(StepLimits.any(step()))
  }

  @Test
  fun `ноль значит «потолка нет», а не «ничего нельзя»`() {
    // Так снимают ограничение, не удаляя поле; трактовать ноль буквально значит запретить всё.
    assertEquals(StepLimits.Verdict.OK, StepLimits.check(50_000, 20, maxTokens = 0, maxSteps = 0))
    assertFalse(StepLimits.any(step(maxTokens = 0, maxSteps = 0)))
  }

  @Test
  fun `потолок токенов срабатывает на превышении, а не на достижении`() {
    assertEquals(StepLimits.Verdict.OK, StepLimits.check(1_000, 0, maxTokens = 1_000, maxSteps = null))
    assertEquals(StepLimits.Verdict.TOKENS, StepLimits.check(1_001, 0, maxTokens = 1_000, maxSteps = null))
  }

  @Test
  fun `maxSteps 5 значит «пять вызовов можно, шестой нельзя»`() {
    assertEquals(StepLimits.Verdict.OK, StepLimits.check(0, 5, maxTokens = null, maxSteps = 5))
    assertEquals(StepLimits.Verdict.STEPS, StepLimits.check(0, 6, maxTokens = null, maxSteps = 5))
  }

  @Test
  fun `токены важнее вызовов, когда нарушены оба`() {
    // Деньги называются первыми: они и есть причина, по которой потолок ставят.
    assertEquals(StepLimits.Verdict.TOKENS, StepLimits.check(9_999, 99, maxTokens = 10, maxSteps = 1))
  }

  @Test
  fun `один заданный потолок уже повод считать`() {
    assertTrue(StepLimits.any(step(maxTokens = 100)))
    assertTrue(StepLimits.any(step(maxSteps = 3)))
  }
}
