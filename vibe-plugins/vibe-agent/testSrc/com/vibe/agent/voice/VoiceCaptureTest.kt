// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

  /** 16-bit little-endian PCM of the given samples. */
  private fun pcmOf(vararg samples: Int): ByteArray =
    ByteArray(samples.size * 2).also { out ->
      samples.forEachIndexed { i, sample ->
        out[i * 2] = (sample and 0xFF).toByte()
        out[i * 2 + 1] = (sample shr 8).toByte()
      }
    }

  @Test
  fun `a recording of zeros is silence, one audible sample is not`() {
    assertTrue(VoiceCapture.isSilent(ByteArray(32_000)), "what an open but forbidden microphone delivers")
    assertTrue(VoiceCapture.isSilent(ByteArray(0)))
    assertFalse(VoiceCapture.isSilent(pcmOf(0, 0, 9_000, 0)))
    assertFalse(VoiceCapture.isSilent(pcmOf(0, 0, -9_000, 0)), "a negative swing is as loud as a positive one")
  }

  @Test
  fun `the silence threshold sits between digital zero and the noise of a live microphone`() {
    val below = VoiceCapture.SILENT_PEAK - 1
    assertTrue(VoiceCapture.isSilent(pcmOf(below, -below, 0)))
    assertFalse(VoiceCapture.isSilent(pcmOf(VoiceCapture.SILENT_PEAK)))
    assertFalse(VoiceCapture.isSilent(pcmOf(-VoiceCapture.SILENT_PEAK)))
    assertFalse(VoiceCapture.isSilent(pcmOf(-32_768)), "the most negative sample must not overflow into silence")
  }

  @Test
  fun `a read loop stopped by the person ends without a reason`() {
    val sink = ByteArrayOutputStream()
    var reads = 0
    val end = VoiceCapture.pump({ reads++; 100 }, sink, capBytes = 1_000_000, stopped = { reads >= 3 })
    assertNull(end)
    assertEquals(300, sink.size())
  }

  @Test
  fun `a read loop that reaches the cap says so and stops reading`() {
    val sink = ByteArrayOutputStream()
    var reads = 0
    val end = VoiceCapture.pump({ reads++; 4_096 }, sink, capBytes = 10_000, stopped = { false })
    assertEquals(VoiceCapture.End.Limit, end)
    assertEquals(3, reads, "three chunks pass 10000 bytes")
  }

  @Test
  fun `a device that stops delivering is a reason, not a quiet end`() {
    val end = VoiceCapture.pump({ 0 }, ByteArrayOutputStream(), capBytes = 1_000, stopped = { false })
    assertTrue(end is VoiceCapture.End.Device, end.toString())
  }

  @Test
  fun `a read that fails carries its message, and one that fails after stop is just the line closing`() {
    val failed = VoiceCapture.pump({ throw IllegalStateException("line closed by the system") }, ByteArrayOutputStream(), 1_000) { false }
    assertEquals(VoiceCapture.End.Device("line closed by the system"), failed)
    var stopped = false
    val closing = VoiceCapture.pump({ stopped = true; throw IllegalStateException("closed") }, ByteArrayOutputStream(), 1_000) { stopped }
    assertNull(closing)
  }

  @Test
  fun `an empty read after stop is the line draining and not a failure`() {
    var stopped = false
    val end = VoiceCapture.pump({ stopped = true; 0 }, ByteArrayOutputStream(), 1_000) { stopped }
    assertNull(end)
  }

  @Test
  fun `the loudness of silence is zero and of a full-scale square wave one`() {
    assertEquals(0.0, VoiceCapture.rmsOf(ByteArray(1_000)))
    assertEquals(0.0, VoiceCapture.rmsOf(ByteArray(0)))
    assertEquals(1.0, VoiceCapture.rmsOf(pcmOf(32_767, -32_768, 32_767, -32_768)), 1e-3)
    assertEquals(0.5, VoiceCapture.rmsOf(pcmOf(16_384, -16_384, 16_384, -16_384)), 1e-9)
  }

  @Test
  fun `the loudness counts only the bytes that were read, not the rest of the chunk`() {
    val chunk = pcmOf(16_384, 16_384, 32_767, 32_767)
    assertEquals(0.5, VoiceCapture.rmsOf(chunk, length = 4), 1e-9)
    assertEquals(0.0, VoiceCapture.rmsOf(chunk, length = 1), "half a sample is not a sample")
  }

  @Test
  fun `the level is taken from the very bytes that go to the recording`() {
    val sink = ByteArrayOutputStream()
    val levels = mutableListOf<Double>()
    val loud = pcmOf(*IntArray(100) { 16_384 })
    var reads = 0
    VoiceCapture.pump(
      read = { buffer -> loud.copyInto(buffer); reads++; loud.size },
      sink = sink,
      capBytes = 1_000_000,
      onLevel = { levels += it },
    ) { reads >= 3 }
    assertEquals(3, levels.size)
    assertTrue(levels.all { kotlin.math.abs(it - 0.5) < 1e-9 }, levels.toString())
    assertEquals(3 * loud.size, sink.size())
  }

  @Test
  fun `a speaking voice moves the pulse`() {
    // A tone at a tenth of full scale is a normal voice: the pulse must see it, and silence must leave it still
    val voice = pcmOf(*IntArray(200) { if (it % 2 == 0) 3_276 else -3_276 })
    assertTrue(com.vibe.agent.voice.VoicePulse.scale(com.vibe.agent.voice.VoicePulse.level(VoiceCapture.rmsOf(voice))) > 1.35)
    assertEquals(1.0, com.vibe.agent.voice.VoicePulse.scale(com.vibe.agent.voice.VoicePulse.level(VoiceCapture.rmsOf(ByteArray(400)))))
  }
}
