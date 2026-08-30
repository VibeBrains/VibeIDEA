// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.fim

import com.vibe.agent.i18n.VibeI18n.t

/**
 * How the autocomplete is actually doing: hits, refusals, failures and latency.
 *
 * Until this existed nothing could be said about FIM at all — not whether it answers in time, not
 * how often the model is asked for something it cannot help with. Percentiles rather than an
 * average on purpose: an average latency hides exactly the tail that makes an autocomplete feel
 * broken.
 *
 * Latencies are kept in a bounded ring — metrics must not become a memory leak of their own.
 */
class FimMetrics(private val window: Int = DEFAULT_WINDOW) {
  private val latencies = ArrayDeque<Long>()

  var requested: Long = 0
    private set
  var served: Long = 0
    private set
  var refused: Long = 0
    private set
  var failed: Long = 0
    private set
  var empty: Long = 0
    private set

  @Synchronized fun refusedToPredict() { refused++ }

  @Synchronized fun answered(latencyMs: Long, hadText: Boolean) {
    requested++
    if (hadText) served++ else empty++
    latencies.addLast(latencyMs)
    while (latencies.size > window) latencies.removeFirst()
  }

  @Synchronized fun failure() { requested++; failed++ }

  @Synchronized fun percentile(p: Int): Long {
    if (latencies.isEmpty()) return 0
    val sorted = latencies.sorted()
    val index = ((p / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
    return sorted[index]
  }

  @Synchronized fun snapshot(cache: FimCache): String {
    val total = cache.hits + cache.misses
    val hitRate = if (total == 0L) 0 else (cache.hits * 100 / total)
    return listOf(
      t("fim.metrics.requests", "requested" to requested, "served" to served, "empty" to empty, "failed" to failed),
      t("fim.metrics.refusals", "refused" to refused),
      t("fim.metrics.cache", "hits" to cache.hits, "misses" to cache.misses,
        "rate" to hitRate, "evictions" to cache.evictions, "size" to cache.size()),
      t("fim.metrics.latency", "p50" to percentile(50), "p95" to percentile(95), "max" to percentile(100)),
    ).joinToString("\n")
  }

  @Synchronized fun reset() {
    latencies.clear()
    requested = 0; served = 0; refused = 0; failed = 0; empty = 0
  }

  companion object {
    const val DEFAULT_WINDOW = 200
  }
}
