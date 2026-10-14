// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import com.vibe.agent.resilience.RetryPause
import com.vibe.agent.resilience.RetryPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The words of a pause before a repeat: the cause with its HTTP status, the countdown, which repeat of how many
 *
 * The strings are asserted as the person reads them: a countdown that says «1» when two seconds are left, or names the wrong repeat
 * Is the kind of mistake a screenshot does not show
 */
class RetryPauseTextTest {
  private fun pause(status: Int?, attempt: Int = 1, delayMs: Long = 1_000, kind: RetryPolicy.Kind = RetryPolicy.Kind.TRANSIENT) =
    RetryPause(attempt, RetryPolicy.MAX_ATTEMPTS - 1, delayMs, status, kind, detail = null)

  @Test
  fun `the cause is classified by the status, and no status means the provider was not reached`() {
    assertEquals(RetryPauseText.Reason.NETWORK, RetryPauseText.reasonOf(null))
    assertEquals(RetryPauseText.Reason.RATE_LIMIT, RetryPauseText.reasonOf(429))
    assertEquals(RetryPauseText.Reason.TIMEOUT, RetryPauseText.reasonOf(408))
    for (status in listOf(500, 502, 503, 504)) assertEquals(RetryPauseText.Reason.SERVER, RetryPauseText.reasonOf(status))
  }

  @Test
  fun `a server failure names the HTTP status`() {
    assertEquals("Сервер провайдера не ответил (HTTP 500)", RetryPauseText.reason(pause(500)))
    assertEquals("Провайдер просит подождать (HTTP 429)", RetryPauseText.reason(pause(429)))
    assertEquals("Нет связи с провайдером", RetryPauseText.reason(pause(null)))
  }

  @Test
  fun `the countdown names the repeat and how many there are`() {
    assertEquals("Повтор через 1с · повтор 1 из 3", RetryPauseText.countdown(pause(500), secondsLeft = 1))
    assertEquals("Повтор через 4с · повтор 3 из 3", RetryPauseText.countdown(pause(500, attempt = 3), secondsLeft = 4))
  }

  @Test
  fun `seconds left round up, so the card never reads zero while there is still time`() {
    assertEquals(2, RetryPauseText.secondsLeft(deadlineMs = 2_000, nowMs = 0))
    assertEquals(2, RetryPauseText.secondsLeft(deadlineMs = 2_000, nowMs = 1))
    assertEquals(1, RetryPauseText.secondsLeft(deadlineMs = 2_000, nowMs = 1_000))
    assertEquals(1, RetryPauseText.secondsLeft(deadlineMs = 2_000, nowMs = 1_999))
    assertEquals(0, RetryPauseText.secondsLeft(deadlineMs = 2_000, nowMs = 2_000))
  }

  @Test
  fun `a deadline that has passed reads zero, never a negative`() {
    assertEquals(0, RetryPauseText.secondsLeft(deadlineMs = 2_000, nowMs = 9_999))
  }

  @Test
  fun `the one-line form carries the cause, the status and the countdown`() {
    val line = RetryPauseText.line(pause(503, attempt = 2, delayMs = 2_000))
    assertTrue("HTTP 503" in line, line)
    assertTrue("повтор 2 из 3" in line, line)
    assertTrue("через 2с" in line, line)
    assertFalse('{' in line, "an unfilled placeholder is left in: $line")
  }

  @Test
  fun `every pause the policy makes can be put into words`() {
    // From the first pause to the last: the countdown for each is built from the same numbers the policy gives
    for (attempt in 1 until RetryPolicy.MAX_ATTEMPTS) {
      val delay = RetryPolicy.delayMs(attempt, RetryPolicy.Kind.TRANSIENT)
      val line = RetryPauseText.line(pause(500, attempt, delay))
      assertTrue("повтор $attempt из ${RetryPolicy.MAX_ATTEMPTS - 1}" in line, line)
    }
  }
}
