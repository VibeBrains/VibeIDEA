// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import com.vibe.agent.sound.SoundSynth
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sounds of a recording, made of notes, and the volume they are played at. */
class VoiceCueTest {
  private fun samples(pcm: ByteArray): List<Int> =
    (0 until pcm.size / 2).map { (pcm[2 * it].toInt() and 0xFF) or (pcm[2 * it + 1].toInt() shl 8) }

  @Test
  fun `each cue is two short notes and lasts as long as it says`() {
    val bytes = SoundSynth.SAMPLE_RATE * VoiceCue.DURATION_MS / 1000 * 2
    VoiceCue.Kind.entries.forEach { assertEquals(bytes, VoiceCue.pcm(it).size, it.toString()) }
    assertTrue(VoiceCue.DURATION_MS < 300, "a start that lasts longer than the pause before the first word gets into the recording")
  }

  @Test
  fun `the start rises and the stop falls, so they are told apart by ear`() {
    val start = VoiceCue.pcm(VoiceCue.Kind.START)
    val stop = VoiceCue.pcm(VoiceCue.Kind.STOP)
    assertNotEquals(start.toList(), stop.toList())
    val half = start.size / 2
    assertContentEquals(start.copyOfRange(0, half), stop.copyOfRange(half, stop.size), "the notes of the start, the other way round")
    assertContentEquals(start.copyOfRange(half, start.size), stop.copyOfRange(0, half))
  }

  @Test
  fun `a cue is audible but never clips, and it starts and ends in silence so it does not click`() {
    VoiceCue.Kind.entries.forEach { kind ->
      val s = samples(VoiceCue.pcm(kind))
      val peak = s.maxOf { abs(it) }
      assertTrue(peak > 5_000, "$kind is too quiet: $peak")
      assertTrue(peak < Short.MAX_VALUE / 2, "$kind is too close to clipping: $peak")
      assertEquals(0, s.first())
      assertTrue(abs(s.last()) < 200, "$kind ends with a step: ${s.last()}")
    }
  }

  @Test
  fun `the note is the sine of its frequency`() {
    val note = samples(SoundSynth.tone(1_000.0, 100))
    val rate = SoundSynth.SAMPLE_RATE
    val middle = note.size / 2
    // At full envelope the sample is the sine times the amplitude of the signals
    val expected = (sin(2 * Math.PI * 1_000.0 * middle / rate) * Short.MAX_VALUE * 0.35).toInt()
    assertEquals(expected, note[middle])
  }

  @Test
  fun `the volume is a gain in decibels, so that the slider is linear to the ear`() {
    assertEquals(0.0, SoundSynth.gainDb(100)!!, 1e-9)
    assertEquals(20 * log10(0.5), SoundSynth.gainDb(50)!!, 1e-9)
    assertEquals(20 * log10(0.7), SoundSynth.gainDb(70)!!, 1e-9)
    assertNull(SoundSynth.gainDb(0), "zero is silence, not an extremely quiet sound")
    assertEquals(SoundSynth.gainDb(100), SoundSynth.gainDb(250), "a slider past its end is its end")
    assertNull(SoundSynth.gainDb(-5))
  }

  @Test
  fun `the chime of a finished turn is made of the same notes it was made of before they were shared`() {
    // The reference is the formula the chime had in its own file: 90 ms, 880 Hz, 44.1 kHz, a fade of an eighth at both ends
    val millis = 90
    val total = millis * 44_100 / 1000
    val fade = total / 8
    val reference = (0 until total).map { i ->
      val envelope = when {
        i < fade -> i.toDouble() / fade
        i > total - fade -> (total - i).toDouble() / fade
        else -> 1.0
      }
      (sin(2 * Math.PI * 880.0 * i / 44_100) * envelope * Short.MAX_VALUE * 0.35).toInt()
    }
    assertEquals(reference, samples(SoundSynth.tone(880.0, millis)))
  }
}
