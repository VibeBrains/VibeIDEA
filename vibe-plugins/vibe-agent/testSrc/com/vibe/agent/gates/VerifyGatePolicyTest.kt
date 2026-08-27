// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.gates

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class VerifyGatePolicyTest {
  @Test
  fun offAlwaysCompletes() {
    assertEquals(VerifyGateDecision.COMPLETE, VerifyGatePolicy.decide("off", ran = true, passed = false, attemptsUsed = 0, maxAttempts = 3))
  }

  @Test
  fun launchFailureIsInert() {
    // ran=false (bad command) never blocks completion, even in enforce.
    assertEquals(VerifyGateDecision.COMPLETE, VerifyGatePolicy.decide("enforce", ran = false, passed = false, attemptsUsed = 0, maxAttempts = 3))
  }

  @Test
  fun greenCompletes() {
    assertEquals(VerifyGateDecision.COMPLETE, VerifyGatePolicy.decide("enforce", ran = true, passed = true, attemptsUsed = 0, maxAttempts = 3))
  }

  @Test
  fun warnRedCompletesWithWarning() {
    assertEquals(VerifyGateDecision.WARN_COMPLETE, VerifyGatePolicy.decide("warn", ran = true, passed = false, attemptsUsed = 0, maxAttempts = 3))
  }

  @Test
  fun enforceRedBouncesThenStops() {
    assertEquals(VerifyGateDecision.BOUNCE, VerifyGatePolicy.decide("enforce", ran = true, passed = false, attemptsUsed = 0, maxAttempts = 2))
    assertEquals(VerifyGateDecision.BOUNCE, VerifyGatePolicy.decide("enforce", ran = true, passed = false, attemptsUsed = 1, maxAttempts = 2))
    assertEquals(VerifyGateDecision.STOP, VerifyGatePolicy.decide("enforce", ran = true, passed = false, attemptsUsed = 2, maxAttempts = 2))
  }

  @Test
  fun zeroMaxAttemptsStopsNotLoops() {
    // Floor at 1: a 0/garbage maxAttempts still stops rather than looping forever.
    assertEquals(VerifyGateDecision.BOUNCE, VerifyGatePolicy.decide("enforce", ran = true, passed = false, attemptsUsed = 0, maxAttempts = 0))
    assertEquals(VerifyGateDecision.STOP, VerifyGatePolicy.decide("enforce", ran = true, passed = false, attemptsUsed = 1, maxAttempts = 0))
  }
}
