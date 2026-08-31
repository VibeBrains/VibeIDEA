// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.resilience

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetryPolicyTest {
  @Test
  fun `a rate limit is a queue, not an error`() {
    assertEquals(RetryPolicy.Kind.RATE_LIMIT, RetryPolicy.classify(429))
  }

  @Test
  fun `server-side failures are transient`() {
    assertEquals(RetryPolicy.Kind.TRANSIENT, RetryPolicy.classify(500))
    assertEquals(RetryPolicy.Kind.TRANSIENT, RetryPolicy.classify(503))
    assertEquals(RetryPolicy.Kind.TRANSIENT, RetryPolicy.classify(408))
  }

  @Test
  fun `a bad key is fatal and must not be retried`() {
    // Три попытки с неверным ключом лишь втрое удлиняют путь до честного сообщения.
    assertEquals(RetryPolicy.Kind.FATAL, RetryPolicy.classify(401))
    assertEquals(RetryPolicy.Kind.FATAL, RetryPolicy.classify(400))
    // 403 и 404 раньше считались тем же самым — и это была ошибка: доступ, который отозвали,
    // чинится другим провайдером, а ключ — нет. Разбор ниже.
  }

  @Test
  fun `network failures without a status are transient`() {
    assertEquals(RetryPolicy.Kind.TRANSIENT, RetryPolicy.classify(null, java.net.SocketTimeoutException()))
    assertEquals(RetryPolicy.Kind.TRANSIENT, RetryPolicy.classify(null, java.net.ConnectException()))
    assertEquals(RetryPolicy.Kind.TRANSIENT, RetryPolicy.classify(null, java.io.IOException("reset")))
  }

  @Test
  fun `a user stop is never retried behind their back`() {
    assertEquals(RetryPolicy.Kind.FATAL, RetryPolicy.classify(null, java.io.InterruptedIOException("остановлено пользователем")))
  }

  @Test
  fun `an unknown failure is fatal rather than hammered`() {
    assertEquals(RetryPolicy.Kind.FATAL, RetryPolicy.classify(null, IllegalStateException("что-то не то")))
    assertEquals(RetryPolicy.Kind.FATAL, RetryPolicy.classify(null, null))
  }

  @Test
  fun `the provider's own retry-after wins over our guess`() {
    assertEquals(30_000, RetryPolicy.delayMs(attempt = 1, kind = RetryPolicy.Kind.RATE_LIMIT, retryAfterSeconds = 30))
  }

  @Test
  fun `the backoff doubles and is capped`() {
    assertEquals(2_000, RetryPolicy.delayMs(1, RetryPolicy.Kind.TRANSIENT))
    assertEquals(4_000, RetryPolicy.delayMs(2, RetryPolicy.Kind.TRANSIENT))
    assertEquals(8_000, RetryPolicy.delayMs(3, RetryPolicy.Kind.TRANSIENT))
    assertEquals(RetryPolicy.MAX_DELAY_MS, RetryPolicy.delayMs(20, RetryPolicy.Kind.TRANSIENT))
  }

  @Test
  fun `an absurd retry-after is clamped instead of freezing the chat`() {
    assertEquals(RetryPolicy.MAX_DELAY_MS, RetryPolicy.delayMs(1, RetryPolicy.Kind.RATE_LIMIT, retryAfterSeconds = 86_400))
  }

  @Test
  fun `retries stop at the attempt limit and never start for fatal`() {
    assertTrue(RetryPolicy.shouldRetry(RetryPolicy.Kind.RATE_LIMIT, attempt = 1))
    assertFalse(RetryPolicy.shouldRetry(RetryPolicy.Kind.RATE_LIMIT, attempt = RetryPolicy.MAX_ATTEMPTS))
    assertFalse(RetryPolicy.shouldRetry(RetryPolicy.Kind.FATAL, attempt = 1))
  }

  @Test
  fun `retry-after is read only in its seconds form`() {
    assertEquals(12, RetryPolicy.retryAfterSeconds("12"))
    // HTTP-дата — тоже валидный Retry-After, но угадывать её разбор хуже, чем откатиться к бэкоффу.
    assertNull(RetryPolicy.retryAfterSeconds("Wed, 21 Oct 2026 07:28:00 GMT"))
    assertNull(RetryPolicy.retryAfterSeconds(null))
  }

  @Test
  fun `the status is recovered from our own error text`() {
    assertEquals(429, RetryPolicy.statusFromMessage("HTTP 429: rate limit reached"))
    assertNull(RetryPolicy.statusFromMessage("connection reset"))
  }

  @Test
  fun `отозванный доступ к модели — не то же, что плохой ключ`() {
    // 401 неверен и у следующего провайдера; 402/403/404 — ровно то, что следующий провайдер чинит.
    assertEquals(RetryPolicy.Kind.FATAL, RetryPolicy.classify(401))
    assertEquals(RetryPolicy.Kind.UNAVAILABLE, RetryPolicy.classify(403))
    assertEquals(RetryPolicy.Kind.UNAVAILABLE, RetryPolicy.classify(404))
    assertEquals(RetryPolicy.Kind.UNAVAILABLE, RetryPolicy.classify(402))
  }

  @Test
  fun `недоступную модель не ждут и не повторяют`() {
    assertEquals(0L, RetryPolicy.delayMs(1, RetryPolicy.Kind.UNAVAILABLE, retryAfterSeconds = 30))
    assertFalse(RetryPolicy.shouldRetry(RetryPolicy.Kind.UNAVAILABLE, attempt = 1))
  }

  @Test
  fun `фолбэк при недоступной модели идёт сразу, а при плохом ключе не идёт вовсе`() {
    assertTrue(FailoverPlan.shouldFailOver(RetryPolicy.Kind.UNAVAILABLE, retriesExhausted = false))
    assertFalse(FailoverPlan.shouldFailOver(RetryPolicy.Kind.FATAL, retriesExhausted = true))
    assertFalse(FailoverPlan.shouldFailOver(RetryPolicy.Kind.RATE_LIMIT, retriesExhausted = false))
  }
}
