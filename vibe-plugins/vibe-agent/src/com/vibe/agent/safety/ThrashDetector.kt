// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.safety

/**
 * Failure that burns money without looking like a loop.
 *
 * [LoopDetector] watches the SHAPE of calls and catches an agent doing the same thing over and
 * over. It sees nothing when the calls differ but keep failing, and nothing at all when a call
 * ends in a timeout that the surrounding code counts as an ending like any other. VibeIDE paid for
 * that hole in cash: their error counter reset on every success, a command timeout counted as
 * success, and the model restarted a hung command for three sessions — about a million and a half
 * tokens each.
 *
 * Two rules, both about the recent past rather than about a strict streak:
 *
 *  • **the same call timing out again and again** — waiting has already been tried, and the answer
 *    did not change;
 *  • **N failures out of the last M calls** — errors alternating with successes still burn the
 *    budget, and a counter that resets on every success never notices.
 *
 * Pure: a list of outcomes in, a verdict out.
 */
object ThrashDetector {
  enum class Outcome { OK, ERROR, TIMEOUT }

  data class Event(val fingerprint: String, val outcome: Outcome)

  enum class Verdict { OK, REPEATED_TIMEOUT, THRASH }

  data class Finding(val verdict: Verdict, val detail: String, val count: Int)

  /** How far back «recently» reaches. */
  const val WINDOW = 10

  /**
   * Failures within the window that mean the turn is not going anywhere.
   *
   * Half the window, and the half was chosen by writing the test first: at six out of ten a run
   * that alternates failure with success — the exact shape this rule exists to catch — never trips,
   * because every second call resets nothing but keeps the count just under the line.
   */
  const val FAILURE_LIMIT = 5

  /** The same call timing out this many times is not bad luck. */
  const val TIMEOUT_REPEATS = 3

  fun check(
    history: List<Event>,
    window: Int = WINDOW,
    failureLimit: Int = FAILURE_LIMIT,
    timeoutRepeats: Int = TIMEOUT_REPEATS,
  ): Finding {
    val recent = history.takeLast(window)
    if (recent.isEmpty()) return Finding(Verdict.OK, "", 0)

    // Timeouts first: they are the more specific diagnosis, and «эта команда висит» is a more
    // useful sentence than «много ошибок».
    val timeouts = recent.filter { it.outcome == Outcome.TIMEOUT }
      .groupBy { it.fingerprint }
      .maxByOrNull { it.value.size }
    if (timeouts != null && timeouts.value.size >= timeoutRepeats) {
      return Finding(Verdict.REPEATED_TIMEOUT, timeouts.key, timeouts.value.size)
    }

    val failures = recent.count { it.outcome != Outcome.OK }
    // The window has to be full: three failures out of three calls is a rough start, not a pattern.
    if (recent.size >= window && failures >= failureLimit) {
      return Finding(Verdict.THRASH, "", failures)
    }
    return Finding(Verdict.OK, "", failures)
  }

  /**
   * Does this failure look like a timeout?
   *
   * Read from the words the agent used, because ACP reports one failure status and nothing finer.
   * Detection DATA, not interface text — an English agent says «timed out», a Russian one «истекло».
   */
  fun looksLikeTimeout(text: String?): Boolean {
    val lower = text?.lowercase() ?: return false
    return TIMEOUT_WORDS.any { it in lower }
  }

  private val TIMEOUT_WORDS = listOf("timeout", "timed out", "таймаут", "истекло", "не дождал")

  /** Keeps the history bounded; nothing older than the window can change the verdict. */
  fun trim(history: List<Event>, window: Int = WINDOW): List<Event> =
    if (history.size <= window) history else history.takeLast(window)
}
