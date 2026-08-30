// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.safety

/**
 * The other half of the same insurance: the loop detector catches an agent that is doing too much,
 * this catches one that has stopped doing anything.
 *
 * A hung turn looks exactly like a thinking turn — the same spinner, the same silence. Without a
 * clock, the honest answer to «оно ещё работает?» is a shrug, and people find out by leaving it
 * overnight. So silence is measured, said out loud once, and after twice the same wait the turn is
 * ended rather than left pretending.
 *
 * Pure: the clock is an argument, because a safety mechanism that can only be tested by waiting
 * ten minutes is a mechanism nobody tests.
 */
object DeadManSwitch {
  /** Default patience: long enough for a slow model, short enough to notice within a coffee. */
  const val DEFAULT_SILENCE_MS = 5 * 60 * 1000L

  enum class Verdict {
    /** Something happened recently enough. */
    ALIVE,
    /** Silent past the limit — say it once. */
    STALE,
    /** Silent twice past the limit — the turn is ended. */
    DEAD,
  }

  /**
   * [lastActivityMs] is the last sign of life: a token, a tool call, an update. Not the turn's
   * start — a turn that streamed for an hour and then froze is frozen, however long it worked.
   */
  fun check(lastActivityMs: Long, nowMs: Long, silenceMs: Long = DEFAULT_SILENCE_MS): Verdict {
    if (silenceMs <= 0) return Verdict.ALIVE
    val silent = nowMs - lastActivityMs
    return when {
      silent >= silenceMs * 2 -> Verdict.DEAD
      silent >= silenceMs -> Verdict.STALE
      else -> Verdict.ALIVE
    }
  }

  /** Minutes of silence, for the line shown to the user. */
  fun silentMinutes(lastActivityMs: Long, nowMs: Long): Long = ((nowMs - lastActivityMs) / 60_000L).coerceAtLeast(0)
}
