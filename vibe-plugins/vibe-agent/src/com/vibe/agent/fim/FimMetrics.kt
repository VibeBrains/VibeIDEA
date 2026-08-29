// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.fim

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
    return buildString {
      append("Запросов к модели: ").append(requested)
      append(" · с подсказкой: ").append(served)
      append(" · пустых: ").append(empty)
      append(" · ошибок: ").append(failed).append("\n")
      append("Отказов «не предсказываем»: ").append(refused)
      append(" — столько запросов не ушло в сеть по форме места в коде\n")
      append("Кэш: попаданий ").append(cache.hits).append(", промахов ").append(cache.misses)
      append(" (").append(hitRate).append("%), вытеснено ").append(cache.evictions)
      append(", записей ").append(cache.size()).append("\n")
      append("Задержка модели: p50 ").append(percentile(50)).append(" мс")
      append(" · p95 ").append(percentile(95)).append(" мс")
      append(" · максимум ").append(percentile(100)).append(" мс")
    }
  }

  @Synchronized fun reset() {
    latencies.clear()
    requested = 0; served = 0; refused = 0; failed = 0; empty = 0
  }

  companion object {
    const val DEFAULT_WINDOW = 200
  }
}
