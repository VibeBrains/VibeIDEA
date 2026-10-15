// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.sound

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.Line
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

/**
 * The few notes our signals are made of, and how they are played
 *
 * Every signal is synthesised, never shipped as a file: no asset, no licence question, no decoder (the JDK plays WAV and not MP3),
 * and the same sound on every platform
 * One place for the notes and the volume, so the chime of a finished turn and the cues of a recording cannot drift apart
 */
object SoundSynth {
  const val SAMPLE_RATE = 44_100
  private const val SAMPLE_BITS = 16
  private const val CHANNELS = 1
  private const val BYTES_PER_SAMPLE = 2
  private const val MS_PER_SECOND = 1000

  /** Loud enough to hear over a room, far from clipping when two notes follow each other */
  private const val AMPLITUDE = 0.35

  /** One eighth of a note rises from silence and one eighth falls to it, so the edges do not click */
  private const val FADE_DIVISOR = 8

  /** Below this a volume is silence; the gain formula has no answer for zero */
  private const val SILENT_VOLUME = 0.0001
  private const val DECIBELS_PER_DECADE = 20.0

  fun format(): AudioFormat = AudioFormat(SAMPLE_RATE.toFloat(), SAMPLE_BITS, CHANNELS, true, false)

  /** One note as 16-bit little-endian PCM, with a short fade at both ends */
  fun tone(frequencyHz: Double, millis: Int): ByteArray {
    val samples = millis * SAMPLE_RATE / MS_PER_SECOND
    val buffer = ByteArray(samples * BYTES_PER_SAMPLE)
    val fade = samples / FADE_DIVISOR
    for (i in 0 until samples) {
      val envelope = when {
        i < fade -> i.toDouble() / fade
        i > samples - fade -> (samples - i).toDouble() / fade
        else -> 1.0
      }
      val value = (sin(2 * PI * frequencyHz * i / SAMPLE_RATE) * envelope * Short.MAX_VALUE * AMPLITUDE).toInt()
      buffer[i * BYTES_PER_SAMPLE] = (value and 0xFF).toByte()
      buffer[i * BYTES_PER_SAMPLE + 1] = ((value shr 8) and 0xFF).toByte()
    }
    return buffer
  }

  /**
   * The gain in decibels for a volume slider of 0 to 100, or null for silence
   * The slider is linear and the ear is not: mapped through the logarithm, 50 sounds like half and not like 95
   */
  fun gainDb(volumePercent: Int): Double? {
    val volume = volumePercent.coerceIn(0, 100) / 100.0
    return if (volume <= SILENT_VOLUME) null else DECIBELS_PER_DECADE * log10(volume)
  }

  /** Sets the master gain of [line] to [volumePercent] when the line has one */
  fun applyVolume(line: Line, volumePercent: Int) {
    if (!line.isControlSupported(FloatControl.Type.MASTER_GAIN)) return
    val control = line.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
    val gain = gainDb(volumePercent)?.toFloat() ?: control.minimum
    control.value = gain.coerceIn(control.minimum, control.maximum)
  }

  /** Plays [pcm] (our format) and returns when it has been heard; blocks, so never on the UI thread */
  fun play(pcm: ByteArray, volumePercent: Int) {
    val format = format()
    val line = AudioSystem.getSourceDataLine(format)
    line.open(format)
    try {
      applyVolume(line, volumePercent)
      line.start()
      line.write(pcm, 0, pcm.size)
      line.drain()
    }
    finally {
      line.close()
    }
  }
}
