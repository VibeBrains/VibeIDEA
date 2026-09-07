// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.safety

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Остановка и петля выглядят одинаково, а решения противоположные. */
class StopCauseTest {
  @Test
  fun `своё решение знаем точнее чужого ответа`() {
    assertEquals(StopCause.Cause.USER,
                 StopCause.of(stoppedByUser = true, breakerTripped = true, failureMessage = "503", finishedCleanly = false))
    assertEquals(StopCause.Cause.BREAKER,
                 StopCause.of(stoppedByUser = false, breakerTripped = true, failureMessage = "503", finishedCleanly = false))
  }

  @Test
  fun `отказ провайдера читается из его же ответа`() {
    assertEquals(StopCause.Cause.UNAVAILABLE,
                 StopCause.of(false, false, "HTTP 503 Service Unavailable", false))
    assertEquals(StopCause.Cause.UNAVAILABLE, StopCause.of(false, false, "read timed out", false))
  }

  @Test
  fun `плохой ключ недоступностью не считается`() {
    // Ключ сам не починится: предлагать возобновление значит обещать то, чего не будет.
    assertFalse(StopCause.looksUnavailable("HTTP 401 Unauthorized"))
    assertFalse(StopCause.looksUnavailable("403 Forbidden: invalid api key"))
    assertEquals(StopCause.Cause.UNKNOWN, StopCause.of(false, false, "HTTP 401", false))
  }

  @Test
  fun `возобновляем только внешнюю недоступность`() {
    assertTrue(StopCause.resumable(StopCause.Cause.UNAVAILABLE))
    assertFalse(StopCause.resumable(StopCause.Cause.BREAKER), "вернётся тот же круг за те же деньги")
    assertFalse(StopCause.resumable(StopCause.Cause.USER))
    assertFalse(StopCause.resumable(StopCause.Cause.DONE))
  }

  @Test
  fun `незавершённым считается всё, кроме честного конца`() {
    assertFalse(StopCause.unfinished(StopCause.Cause.DONE))
    assertTrue(StopCause.unfinished(StopCause.Cause.USER))
    assertTrue(StopCause.unfinished(StopCause.Cause.UNKNOWN))
  }

  @Test
  fun `чистый конец — это DONE, а не догадка`() {
    assertEquals(StopCause.Cause.DONE, StopCause.of(false, false, null, finishedCleanly = true))
    assertEquals(StopCause.Cause.UNKNOWN, StopCause.of(false, false, null, finishedCleanly = false))
  }
}
