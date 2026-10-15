// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How loud the voice is becomes how big the icon is, with the numbers VibeIDE uses. */
class VoicePulseTest {
  @Test
  fun `the loudness is multiplied by the gain and held to the range of zero to one`() {
    assertEquals(0.0, VoicePulse.level(0.0))
    assertEquals(0.18, VoicePulse.level(0.01), 1e-9)
    assertEquals(0.9, VoicePulse.level(0.05), 1e-9)
    assertEquals(1.0, VoicePulse.level(0.2), "a loud voice does not leave the range")
    assertEquals(0.0, VoicePulse.level(-0.5))
  }

  @Test
  fun `the icon is its own size in silence and swells by at most four tenths`() {
    assertEquals(1.0, VoicePulse.scale(0.0))
    assertEquals(1.25, VoicePulse.scale(0.5), 1e-9)
    assertEquals(1.4, VoicePulse.scale(0.8), 1e-9)
    assertEquals(1.4, VoicePulse.scale(1.0), "the loudest voice stops at the cap")
    assertEquals(1.4, VoicePulse.scale(7.0))
    assertEquals(1.0, VoicePulse.scale(-1.0))
  }

  @Test
  fun `the swelling follows the loudness and never shrinks below the icon`() {
    val sizes = (0..10).map { VoicePulse.scale(it / 10.0) }
    assertEquals(sizes, sizes.sorted())
    assertTrue(sizes.all { it in 1.0..1.4 })
  }

  @Test
  fun `a speaking voice is audible to the eye and the noise of a quiet room is not`() {
    // A few hundredths of full scale is speech in a quiet room, a few thousandths the floor of a live microphone
    assertTrue(VoicePulse.scale(VoicePulse.level(0.04)) > 1.3)
    assertTrue(VoicePulse.scale(VoicePulse.level(0.002)) < 1.03)
  }

  @Test
  fun `the shown level covers two thirds of the way in one time constant`() {
    val smoother = VoicePulse.Smoother()
    val shown = smoother.next(1.0, VoicePulse.SMOOTHING_MS.toLong())
    assertEquals(1.0 - Math.exp(-1.0), shown, 0.01)
    assertTrue(VoicePulse.SMOOTHING_MS in 100.0..150.0, "the smoothing the task asks for is a tenth to a seventh of a second")
  }

  @Test
  fun `it does not jump to the new level and does not move without time`() {
    val smoother = VoicePulse.Smoother()
    assertEquals(0.0, smoother.next(1.0, 0))
    val first = smoother.next(1.0, VoicePulse.FRAME_MS.toLong())
    assertTrue(first in 0.1..0.5, "one frame moves it part of the way: $first")
  }

  @Test
  fun `the same time gives the same level however it is cut into frames`() {
    val whole = VoicePulse.Smoother().next(0.8, 120)
    val cut = VoicePulse.Smoother().apply { repeat(4) { next(0.8, 30) } }.shown
    assertTrue(abs(whole - cut) < 1e-9, "$whole vs $cut")
  }

  @Test
  fun `it falls back when the voice stops, and a reset starts from silence`() {
    val smoother = VoicePulse.Smoother()
    repeat(20) { smoother.next(1.0, 33) }
    val loud = smoother.shown
    repeat(20) { smoother.next(0.0, 33) }
    assertTrue(smoother.shown < loud / 10, "${smoother.shown} after $loud")
    smoother.reset()
    assertEquals(0.0, smoother.shown)
  }

  @Test
  fun `a level outside the range is held to it`() {
    val smoother = VoicePulse.Smoother()
    repeat(50) { smoother.next(5.0, 100) }
    assertTrue(smoother.shown <= 1.0)
  }
}
