// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.voice

import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * Recording the microphone — with the JDK, and with no native library at all.
 *
 * That was the open question this closes: which native library to ship for capture. The answer is
 * none. `javax.sound.sampled` is in the JRE we already ship, works on all three systems, and adds
 * nothing to patch when the next CVE lands in somebody's audio codec. A bundled native recorder
 * would have bought a nicer waveform and a permanent maintenance debt.
 *
 * The format is not a preference, it is a requirement: whisper wants 16 kHz mono 16-bit PCM, and
 * handing it anything else means a resample somewhere else — usually inside ffmpeg, which is the
 * dependency we are trying not to require for a fifteen-second note.
 */
object VoiceCapture {
  const val SAMPLE_RATE = 16_000f
  const val SAMPLE_BITS = 16
  const val CHANNELS = 1

  /** WAV, because it is the one container `AudioSystem.write` produces without a codec. */
  fun format(): AudioFormat = AudioFormat(SAMPLE_RATE, SAMPLE_BITS, CHANNELS, true, false)

  /** True when this machine can record at all — a button that cannot work should not be offered. */
  fun isSupported(format: AudioFormat = format()): Boolean =
    AudioSystem.isLineSupported(DataLine.Info(TargetDataLine::class.java, format))

  /**
   * Longest note we will keep recording, in milliseconds.
   *
   * A forgotten recording is not a small bug: it is a microphone left on. Half an hour of silence
   * would also cost minutes of transcription and produce whisper's hallucinated filler.
   */
  const val MAX_MS = 5 * 60 * 1000L

  /** Shorter than this is a misclick, not a note — transcribing it wastes seconds to say nothing. */
  const val MIN_MS = 700L

  /** Bytes per millisecond at our format; the arithmetic the size checks are made of. */
  fun bytesPerMs(format: AudioFormat = format()): Double =
    format.sampleRate.toDouble() * format.frameSize / 1000.0

  fun tooShort(bytesRecorded: Long, format: AudioFormat = format()): Boolean =
    bytesRecorded < MIN_MS * bytesPerMs(format)

  /**
   * One recording in progress. Started by [start], ended by [stop] — which returns the file, or
   * null when what was captured is too short to be a note.
   */
  class Recording internal constructor(private val line: TargetDataLine, private val target: File) {
    @Volatile private var stopped = false
    private val buffer = java.io.ByteArrayOutputStream()
    private val thread = Thread({ pump() }, "vibe-voice-capture").apply { isDaemon = true; start() }

    val elapsedMs: Long get() = ((buffer.size() / bytesPerMs()).toLong())

    private fun pump() {
      val chunk = ByteArray(4096)
      val cap = (MAX_MS * bytesPerMs()).toLong()
      while (!stopped) {
        val read = line.read(chunk, 0, chunk.size)
        if (read <= 0) break
        buffer.write(chunk, 0, read)
        // The cap is enforced here rather than by a timer: a timer that fires while the line is
        // blocked in read() stops nothing, and «микрофон выключился, но пишет» is the one failure
        // mode of a recorder nobody forgives.
        if (buffer.size() >= cap) break
      }
    }

    /** @return the WAV file, or null when the recording is too short to bother transcribing. */
    fun stop(): File? {
      stopped = true
      runCatching { line.stop() }
      thread.join(2000)
      runCatching { line.close() }
      val bytes = buffer.toByteArray()
      if (tooShort(bytes.size.toLong())) return null
      AudioInputStream(java.io.ByteArrayInputStream(bytes), format(), bytes.size.toLong() / format().frameSize).use {
        AudioSystem.write(it, AudioFileFormat.Type.WAVE, target)
      }
      return target
    }

    /** Throws away what was captured: the person changed their mind, and the file must not appear. */
    fun cancel() {
      stopped = true
      runCatching { line.stop() }
      runCatching { line.close() }
      thread.join(2000)
      buffer.reset()
    }
  }

  /** Opens the microphone. Throws when the system refuses — permissions, or no input device. */
  fun start(target: File): Recording {
    val format = format()
    val line = AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine
    line.open(format)
    line.start()
    return Recording(line, target)
  }
}
