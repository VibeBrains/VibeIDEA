// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

/**
 * How full the model's window is, and what to do about it — decided in numbers, before the
 * request that would overflow it.
 *
 * The failure this prevents is the confusing one: an overflowing window does not announce
 * itself, it makes the model forget the beginning of the conversation. The user sees an
 * answer that ignores what was agreed ten messages ago and concludes the model got dumber.
 *
 * Two independent ceilings, because they fail differently:
 * - the WINDOW is per request: exceeding it silently drops the oldest context;
 * - the SESSION is per chat over time: exceeding it is a bill, not a malfunction.
 */
object ContextBudget {
  /** A nudge, not an interruption — the turn still runs. */
  const val WARN_PERCENT = 75

  /** Past this the next turn asks first: at 90% the answer is already being shaped by what fell out. */
  const val BLOCK_PERCENT = 90

  enum class Verdict {
    OK,
    /** Say it once and continue. */
    WARN,
    /** Ask before spending: compact, continue anyway, or cancel. */
    BLOCK,
    /** The chat's own ceiling, set by the user, is reached. */
    SESSION_EXCEEDED,
  }

  data class Status(val verdict: Verdict, val windowPercent: Int, val sessionUsed: Long, val sessionLimit: Long?)

  fun check(
    usedTokens: Long,
    windowSize: Long,
    sessionUsed: Long = 0,
    sessionLimit: Long? = null,
    warnPercent: Int = WARN_PERCENT,
    blockPercent: Int = BLOCK_PERCENT,
  ): Status {
    val percent = if (windowSize <= 0) 0 else ((usedTokens * 100) / windowSize).toInt().coerceIn(0, 100)
    // The session ceiling wins: it is the user's own decision about money, and a window that
    // still has room does not make an exceeded budget acceptable.
    if (sessionLimit != null && sessionLimit > 0 && sessionUsed >= sessionLimit) {
      return Status(Verdict.SESSION_EXCEEDED, percent, sessionUsed, sessionLimit)
    }
    val verdict = when {
      windowSize <= 0 -> Verdict.OK          // the target never reported a window: silence beats a guess
      percent >= blockPercent -> Verdict.BLOCK
      percent >= warnPercent -> Verdict.WARN
      else -> Verdict.OK
    }
    return Status(verdict, percent, sessionUsed, sessionLimit)
  }

  /**
   * Rough token estimate for the direct-LLM path, where nobody reports usage.
   *
   * Deliberately crude and deliberately PESSIMISTIC: four characters per token is close for
   * English and generous for Cyrillic, and a guard that under-counts is a guard that lets the
   * window overflow while showing green.
   */
  fun estimateTokens(text: String): Long = if (text.isEmpty()) 0 else ((text.length + 3) / 4).toLong()

  fun estimateTokens(texts: Iterable<String>): Long = texts.sumOf { estimateTokens(it) }
}
