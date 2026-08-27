// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.gates

/**
 * Pure VERIFY-GATE policy, VibeIDE `verifyGatePolicy.ts` verbatim. Decides what a
 * finished, file-mutating turn should do given the verify command's result.
 *
 * A launch failure (bad shell, no command) is reported as `ran=false` by the
 * caller, which maps to `COMPLETE` here — a broken gate never locks completion.
 */
enum class VerifyGateDecision { COMPLETE, WARN_COMPLETE, BOUNCE, STOP }

object VerifyGatePolicy {
  /**
   * @param mode off/warn/enforce
   * @param ran whether the command actually ran (false = off, empty command, or launch failure)
   * @param passed exit code 0 within the timeout
   * @param attemptsUsed how many bounces already happened this turn
   * @param maxAttempts enforce ceiling (floored at 1 so a 0/garbage value still stops, never loops)
   */
  fun decide(mode: String, ran: Boolean, passed: Boolean, attemptsUsed: Int, maxAttempts: Int): VerifyGateDecision {
    if (mode == "off" || !ran || passed) return VerifyGateDecision.COMPLETE
    if (mode == "warn") return VerifyGateDecision.WARN_COMPLETE
    // enforce + red:
    val ceiling = maxOf(1, maxAttempts)
    return if (attemptsUsed < ceiling) VerifyGateDecision.BOUNCE else VerifyGateDecision.STOP
  }
}
