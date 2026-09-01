// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.budget

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CumulativeMeterTest {
  @Test
  fun `a running total is recorded as what it added`() {
    // The bug this class exists for: the cost arrived cumulatively and was recorded whole on every
    // update, so the ledger added the running total again and again.
    val meter = CumulativeMeter()
    assertEquals(0.10, meter.advance(0.10))
    assertEquals(0.15, meter.advance(0.25)!!, 1e-9)
    assertEquals(0.75, meter.advance(1.00)!!, 1e-9)
    // Three reports of a turn that cost a dollar add up to a dollar, not to $1.35.
  }

  @Test
  fun `a decrease is not a refund`() {
    // A new turn starts the counter lower; subtracting would make the day's spend silently shrink.
    val meter = CumulativeMeter()
    meter.advance(5.0)
    assertNull(meter.advance(1.0))
    // And accounting continues from the new total rather than waiting to pass the old one.
    assertEquals(0.5, meter.advance(1.5)!!, 1e-9)
  }

  @Test
  fun `nothing added is not an event`() {
    val meter = CumulativeMeter()
    meter.advance(2.0)
    assertNull(meter.advance(2.0))
    assertNull(meter.advance(null))
  }

  @Test
  fun `whole units report an honest zero instead of null`() {
    // Tokens are always known, so their caller wants a number rather than an absence.
    val meter = CumulativeMeter()
    assertEquals(100L, meter.advanceWhole(100))
    assertEquals(50L, meter.advanceWhole(150))
    assertEquals(0L, meter.advanceWhole(150))
    assertEquals(0L, meter.advanceWhole(10))
  }

  @Test
  fun `reset starts the next turn from zero`() {
    val meter = CumulativeMeter()
    meter.advance(9.0)
    meter.reset()
    assertEquals(3.0, meter.advance(3.0))
  }
}
