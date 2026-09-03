// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The arithmetic and the format — the parts that decide what whisper receives and what is a misclick. */
class VoiceCaptureTest {
  @Test
  fun `the format is what whisper expects, so nothing has to resample`() {
    val format = VoiceCapture.format()
    assertEquals(16_000f, format.sampleRate)
    assertEquals(16, format.sampleSizeInBits)
    assertEquals(1, format.channels)
    assertFalse(format.isBigEndian, "WAV is little-endian; a big-endian stream would play as noise")
    assertEquals(2, format.frameSize, "16-bit mono is two bytes a frame")
  }

  @Test
  fun `bytes per millisecond follow from the format`() {
    // 16000 frames/s × 2 bytes = 32000 bytes/s = 32 bytes/ms.
    assertEquals(32.0, VoiceCapture.bytesPerMs())
  }

  @Test
  fun `a recording shorter than the minimum is a misclick`() {
    val justUnder = (VoiceCapture.MIN_MS * VoiceCapture.bytesPerMs()).toLong() - 1
    assertTrue(VoiceCapture.tooShort(justUnder))
    assertFalse(VoiceCapture.tooShort(justUnder + 1))
    assertTrue(VoiceCapture.tooShort(0))
  }

  @Test
  fun `the cap is minutes, not hours`() {
    // A forgotten recording is a microphone left on; the cap is the promise that it ends.
    assertTrue(VoiceCapture.MAX_MS <= 10 * 60 * 1000L)
    assertTrue(VoiceCapture.MAX_MS >= 60 * 1000L, "a minute is too short for a spoken task")
  }
}
