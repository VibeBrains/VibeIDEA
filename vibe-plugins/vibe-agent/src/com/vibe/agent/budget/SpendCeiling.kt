// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.budget

/**
 * Money spent inside a plan's rolling window, against the ceiling for that window.
 *
 * Subscriptions are sold this way and not in tokens: OpenCode Go grants $12 per five hours, $30
 * per week and $60 per month, and how many requests that buys depends on the model chosen. A token
 * ceiling cannot express any of it — the same hundred thousand tokens costs cents on one model and
 * dollars on another, so the number a person is actually rationing is never the one we were
 * counting.
 *
 * Three windows rather than one because they answer different questions: the five-hour window says
 * «сейчас остановись, через час снова можно», the monthly one says «до конца месяца этого больше
 * нет». Reporting only the longest would let a person burn the short window in twenty minutes and
 * learn about it from the provider.
 *
 * Pure: takes the ledger and the current time, returns a verdict. The spending it reads is what
 * the provider itself reported (`costAmount`), never a price list of ours — a table of prices in
 * our code is out of date the day a vendor changes one.
 */
object SpendCeiling {
  const val FIVE_HOURS_MS: Long = 5L * 60 * 60 * 1000
  const val WEEK_MS: Long = 7L * 24 * 60 * 60 * 1000

  /** Thirty days, not a calendar month: the ledger is a rolling window, and so is the answer. */
  const val MONTH_MS: Long = 30L * 24 * 60 * 60 * 1000

  /** A ceiling for one window; `limit <= 0` means the window is not being watched. */
  data class Window(val id: String, val millis: Long, val limit: Double)

  /** All three ceilings; zeroes by default, because a ceiling nobody asked for is a wall. */
  data class Limits(
    val fiveHours: Double = 0.0,
    val week: Double = 0.0,
    val month: Double = 0.0,
  ) {
    fun windows(): List<Window> = listOf(
      Window(FIVE_HOURS, FIVE_HOURS_MS, fiveHours),
      Window(WEEK, WEEK_MS, week),
      Window(MONTH, MONTH_MS, month),
    ).filter { it.limit > 0 }

    val any: Boolean get() = windows().isNotEmpty()
  }

  const val FIVE_HOURS = "5h"
  const val WEEK = "week"
  const val MONTH = "month"

  data class Verdict(val window: Window, val spent: Double, val exceeded: Boolean) {
    val limit: Double get() = window.limit
    val left: Double get() = (window.limit - spent).coerceAtLeast(0.0)

    /** How full the window is, 0..1+; the caller decides what fraction is worth mentioning. */
    val ratio: Double get() = if (window.limit <= 0) 0.0 else spent / window.limit
  }

  /** Money reported by the provider inside the window; entries without a price count as zero. */
  fun spentIn(entries: List<SpendLedger.Entry>, nowMs: Long, windowMs: Long): Double =
    SpendLedger.within(entries, nowMs, windowMs).sumOf { it.costAmount ?: 0.0 }

  /**
   * The verdict for every watched window, the fullest first.
   *
   * All of them rather than the first hit: a person stopped by the five-hour window still needs to
   * know the month is nearly gone, and learning that an hour later is learning it too late.
   */
  fun check(entries: List<SpendLedger.Entry>, nowMs: Long, limits: Limits): List<Verdict> =
    limits.windows()
      .map { window ->
        val spent = spentIn(entries, nowMs, window.millis)
        Verdict(window, spent, exceeded = spent >= window.limit)
      }
      .sortedByDescending { it.ratio }

  /** The window that stops the turn, or null when there is room everywhere. */
  fun blocking(entries: List<SpendLedger.Entry>, nowMs: Long, limits: Limits): Verdict? =
    check(entries, nowMs, limits).firstOrNull { it.exceeded }

  /**
   * The window worth mentioning before it stops anything, or null.
   *
   * A warning at the very edge is a warning nobody can act on — by then the turn is already the
   * one that will be refused.
   */
  fun warning(
    entries: List<SpendLedger.Entry>,
    nowMs: Long,
    limits: Limits,
    threshold: Double = WARN_RATIO,
  ): Verdict? = check(entries, nowMs, limits).firstOrNull { !it.exceeded && it.ratio >= threshold }

  /** Four fifths: early enough to change the model, late enough not to be noise. */
  const val WARN_RATIO = 0.8
}
