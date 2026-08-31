// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.safety

/**
 * The turn that works, answers, spends money — and moves nothing.
 *
 * The two safeties next door catch the loud failures: [LoopDetector] catches an agent doing the
 * same thing over and over, [DeadManSwitch] catches one that stopped saying anything. Neither sees
 * the quiet one, and the quiet one is the expensive one: every turn looks reasonable, prose is
 * produced, tokens are spent, and after five of them not a single file has changed and the plan has
 * not moved a single tick.
 *
 * Silence is the wrong measure here — such a turn is loud. The measure is PROGRESS, and progress is
 * two facts we already record: whether files changed, and whether the plan moved.
 *
 * Pure by design: the whole thing is a function of a list of turns, so it can be tested without an
 * agent, a model or a clock.
 */
object StallDetector {
  /** Turns without progress before we say so. Three, because two is an ordinary pause to think. */
  const val DEFAULT_STALL_TURNS = 3

  /** What one finished turn moved. */
  data class Turn(val changedFiles: Int, val planDone: Int, val planTotal: Int)

  /**
   * Did this turn move anything?
   *
   * A rewritten plan counts as movement: replanning is work, and calling it a stall would nag
   * exactly at the moment the agent is regrouping. Compared with the PREVIOUS turn rather than with
   * an absolute, because a plan that stands at 3 of 7 for five turns has not moved even though its
   * counter is not zero.
   */
  fun moved(turn: Turn, previous: Turn?): Boolean {
    if (turn.changedFiles > 0) return true
    if (previous == null) return turn.planDone > 0 || turn.planTotal > 0
    return turn.planDone != previous.planDone || turn.planTotal != previous.planTotal
  }

  /** How many turns in a row, counting back from the last one, moved nothing. */
  fun stalledTurns(history: List<Turn>): Int {
    var count = 0
    for (index in history.indices.reversed()) {
      if (moved(history[index], history.getOrNull(index - 1))) break
      count++
    }
    return count
  }

  /** Zero or a negative threshold turns the detector off — a switch, not a special case. */
  fun isStalled(history: List<Turn>, threshold: Int = DEFAULT_STALL_TURNS): Boolean =
    threshold > 0 && stalledTurns(history) >= threshold

  /** Keeps the history short: nothing older than the threshold can change the answer. */
  fun trim(history: List<Turn>, threshold: Int = DEFAULT_STALL_TURNS): List<Turn> {
    val keep = (threshold + 1).coerceAtLeast(2)
    return if (history.size <= keep) history else history.takeLast(keep)
  }
}
