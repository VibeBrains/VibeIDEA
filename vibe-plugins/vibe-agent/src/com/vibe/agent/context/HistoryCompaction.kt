// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

/**
 * How much of the oldest conversation a direct model call folds into a summary.
 *
 * The context budget used to tell a person «compact, continue anyway, or cancel» while no compaction
 * existed: the direct model path simply resent the whole transcript until the window overflowed and
 * the model silently forgot the beginning. An ACP agent keeps its own window; the direct path is ours.
 *
 * The rule: when the estimate crosses [TRIGGER_PERCENT] of the window, fold the oldest messages until
 * what remains fits [TARGET_PERCENT], never touching the last [KEEP_RECENT] — the recent exchange is
 * what the next answer is about. The cut only moves forward: an earlier fold is kept as it is, because
 * rewriting it on every turn would break the prompt cache each time.
 *
 * Pure: token estimates in, the number of messages to fold out.
 */
object HistoryCompaction {
  const val TRIGGER_PERCENT = 80
  const val TARGET_PERCENT = 50
  const val KEEP_RECENT = 6

  /**
   * @param estimates tokens per wire message, oldest first.
   * @param window the model's context window in tokens; null or non-positive — nothing is folded.
   * @param alreadyFolded how many oldest messages an earlier fold covers; the result is never below it.
   * @return how many oldest messages the summary must cover; 0 — no fold needed.
   */
  fun foldCount(estimates: List<Long>, window: Int?, alreadyFolded: Int = 0, summaryTokens: Long = 0): Int {
    if (window == null || window <= 0) return alreadyFolded
    val folded = alreadyFolded.coerceIn(0, estimates.size)
    val live = summaryTokens + estimates.drop(folded).sum()
    if (live * 100 < window.toLong() * TRIGGER_PERCENT) return folded
    val limit = estimates.size - KEEP_RECENT
    if (limit <= folded) return folded
    var cut = folded
    var remaining = live
    val target = window.toLong() * TARGET_PERCENT / 100
    while (cut < limit && remaining > target) {
      remaining -= estimates[cut]
      cut++
    }
    return cut
  }
}
