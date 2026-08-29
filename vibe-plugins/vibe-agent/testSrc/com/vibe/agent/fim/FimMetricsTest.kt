// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.fim

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FimMetricsTest {
  @Test
  fun `answers, refusals and failures are counted apart`() {
    val metrics = FimMetrics()
    metrics.answered(100, hadText = true)
    metrics.answered(200, hadText = false)
    metrics.refusedToPredict()
    metrics.failure()

    assertEquals(3, metrics.requested, "отказ «не предсказываем» — не запрос к модели")
    assertEquals(1, metrics.served)
    assertEquals(1, metrics.empty)
    assertEquals(1, metrics.refused)
    assertEquals(1, metrics.failed)
  }

  @Test
  fun `percentiles show the tail, which an average would hide`() {
    val metrics = FimMetrics()
    repeat(99) { metrics.answered(100, hadText = true) }
    metrics.answered(5_000, hadText = true)

    assertEquals(100, metrics.percentile(50))
    assertEquals(5_000, metrics.percentile(100), "самый медленный ответ виден целиком")
  }

  @Test
  fun `the latency window is bounded — metrics must not leak memory`() {
    val metrics = FimMetrics(window = 10)
    repeat(1000) { metrics.answered(it.toLong(), hadText = true) }
    assertTrue(metrics.percentile(0) >= 990, "в окне остались только последние замеры")
  }

  @Test
  fun `an empty history reports zero, not a crash`() {
    assertEquals(0, FimMetrics().percentile(95))
  }

  @Test
  fun `the snapshot names cache hit rate and refusals`() {
    val cache = FimCache(4)
    cache.put("k", "v"); cache.get("k"); cache.get("miss")
    val metrics = FimMetrics()
    metrics.refusedToPredict()
    val text = metrics.snapshot(cache)
    assertTrue(text.contains("50%"), text)
    assertTrue(text.contains("Отказов"), text)
  }
}
