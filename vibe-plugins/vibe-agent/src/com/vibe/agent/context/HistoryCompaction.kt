// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

/**
 * How much of the oldest conversation a direct model call folds into a summary.
 *
 * The context budget used to tell a person «compact, continue anyway, or cancel» while no compaction
 * existed: the direct model path simply resent the whole transcript until the window overflowed and
 * the model silently forgot the beginning. An ACP agent keeps its own window; the direct path is ours.
 *
 * The rule: when the estimate crosses the trigger share of the window, fold the oldest messages until
 * what remains fits the target share, never touching the most recent messages — the recent exchange is
 * what the next answer is about. The cut only moves forward: an earlier fold is kept as it is, because
 * rewriting it on every turn would break the prompt cache each time.
 *
 * The shares are a setting ([Policy]); the defaults are Codex CLI's order of magnitude (it folds at up to
 * 90 % of the window). Tool schemas travel in every request and are counted as [foldCount]'s `fixedTokens`:
 * without them the trigger fired late in exactly the sessions that use tools.
 *
 * Pure: token estimates in, the number of messages to fold out.
 */
object HistoryCompaction {
  const val TRIGGER_PERCENT = 80
  const val TARGET_PERCENT = 50
  const val KEEP_RECENT = 6

  /** When to fold, down to what, and how many of the latest messages stay as they are. */
  data class Policy(
    val triggerPercent: Int = TRIGGER_PERCENT,
    val targetPercent: Int = TARGET_PERCENT,
    val keepRecent: Int = KEEP_RECENT,
  ) {
    /** A target at or above the trigger would fold on every turn; it is kept below the trigger. */
    val effectiveTarget: Int get() = minOf(targetPercent, triggerPercent - 1).coerceAtLeast(1)
  }

  /** Whether what is sent now crosses the trigger. */
  fun overTrigger(
    estimates: List<Long>,
    window: Int?,
    alreadyFolded: Int = 0,
    summaryTokens: Long = 0,
    fixedTokens: Long = 0,
    policy: Policy = Policy(),
  ): Boolean {
    if (window == null || window <= 0) return false
    val folded = alreadyFolded.coerceIn(0, estimates.size)
    val live = fixedTokens + summaryTokens + estimates.drop(folded).sum()
    return live * 100 >= window.toLong() * policy.triggerPercent
  }

  /**
   * @param estimates tokens per wire message, oldest first.
   * @param window the model's context window in tokens; null or non-positive — nothing is folded.
   * @param alreadyFolded how many oldest messages an earlier fold covers; the result is never below it.
   * @param fixedTokens what every request carries besides messages — the tool schemas.
   * @return how many oldest messages the summary must cover; 0 — no fold needed.
   */
  fun foldCount(
    estimates: List<Long>,
    window: Int?,
    alreadyFolded: Int = 0,
    summaryTokens: Long = 0,
    fixedTokens: Long = 0,
    policy: Policy = Policy(),
  ): Int {
    if (window == null || window <= 0) return alreadyFolded
    val folded = alreadyFolded.coerceIn(0, estimates.size)
    if (!overTrigger(estimates, window, folded, summaryTokens, fixedTokens, policy)) return folded
    val limit = estimates.size - policy.keepRecent
    if (limit <= folded) return folded
    var cut = folded
    var remaining = fixedTokens + summaryTokens + estimates.drop(folded).sum()
    val target = window.toLong() * policy.effectiveTarget / 100
    while (cut < limit && remaining > target) {
      remaining -= estimates[cut]
      cut++
    }
    return cut
  }
}
