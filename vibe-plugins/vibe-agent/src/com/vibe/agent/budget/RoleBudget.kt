// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.budget

/**
 * A ceiling per role per day — the thing that makes an unattended pipeline safe to leave running.
 *
 * Per ROLE rather than per run, because the runaway case is not one expensive turn: it is a
 * reviewer that gets restarted forty times by a loop nobody watched. A per-run limit says nothing
 * about that; a daily one stops it.
 *
 * The check is asked BEFORE the step. A budget reported after the spend is a receipt.
 */
object RoleBudget {
  /** Said out loud while there is still room to change something. */
  const val WARN_PERCENT = 80

  enum class Verdict { OK, WARN, EXCEEDED }

  data class Status(val verdict: Verdict, val spent: Long, val limit: Long, val percent: Int)

  fun check(spentTokens: Long, limitTokens: Long): Status {
    // Zero means off, and off must mean off: a limit nobody set turning into a wall mid-work is
    // the fastest way to have the whole mechanism disabled.
    if (limitTokens <= 0) return Status(Verdict.OK, spentTokens, 0, 0)
    val percent = ((spentTokens * 100) / limitTokens).toInt().coerceAtLeast(0)
    val verdict = when {
      spentTokens >= limitTokens -> Verdict.EXCEEDED
      percent >= WARN_PERCENT -> Verdict.WARN
      else -> Verdict.OK
    }
    return Status(verdict, spentTokens, limitTokens, percent)
  }
}
