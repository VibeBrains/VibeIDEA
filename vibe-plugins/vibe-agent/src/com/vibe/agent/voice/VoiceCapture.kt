// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.abs
import kotlin.math.sqrt

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
   * Loudest sample below which a whole recording counts as silence, out of 32768
   *
   * A microphone that is open but not allowed to listen delivers exact zeros
   * A live one has a noise floor far above this, and speech in a quiet room peaks in the thousands
   */
  const val SILENT_PEAK = 8

  /** True when [pcm] (16-bit little-endian, our format) holds nothing louder than [SILENT_PEAK] */
  fun isSilent(pcm: ByteArray, threshold: Int = SILENT_PEAK): Boolean {
    var i = 0
    while (i + 1 < pcm.size) {
      val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
      if (abs(sample) >= threshold) return false
      i += 2
    }
    return true
  }

  /** Full scale of a 16-bit sample; the divisor that turns a sample into a fraction of the loudest sound the format holds */
  private const val FULL_SCALE = 32768.0

  /**
   * Loudness of the first [length] bytes of [pcm] (16-bit little-endian, our format): the root mean square, 0 to 1 of full scale
   * Speech in a quiet room sits near a few hundredths, so the number is small by nature and the picture is what scales it
   */
  fun rmsOf(pcm: ByteArray, length: Int = pcm.size): Double {
    var sum = 0.0
    var count = 0
    var i = 0
    while (i + 1 < length) {
      val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
      sum += sample.toDouble() * sample
      count++
      i += 2
    }
    return if (count == 0) 0.0 else sqrt(sum / count) / FULL_SCALE
  }

  /** Why a recording ended without being stopped by the person */
  sealed interface End {
    /** [MAX_MS] reached: the microphone is closed on purpose */
    data object Limit : End

    /** The device stopped delivering audio, or the read failed */
    data class Device(val reason: String) : End
  }

  /** What a stopped recording came to */
  sealed interface Captured {
    /** Shorter than [MIN_MS]: a misclick */
    data object TooShort : Captured

    /** The device delivered only silence, typically because the system has not allowed the microphone */
    data object Silence : Captured

    /** The audio could not be kept: the file was not written */
    data class Failed(val reason: String) : Captured

    data class Audio(val file: File) : Captured
  }

  /**
   * The read loop, apart from the line: reads into [sink] until [stopped], the cap, or the device gives out
   *
   * @param onLevel the loudness of every chunk as it is read, from the very bytes that go to [sink]
   * @param onFrame every chunk as it is read, the same bytes and only until the next read reuses the buffer
   * @return why it ended on its own, or null when [stopped] ended it
   */
  internal fun pump(
    read: (ByteArray) -> Int,
    sink: ByteArrayOutputStream,
    capBytes: Long,
    onLevel: (Double) -> Unit = {},
    onFrame: (ByteArray, Int) -> Unit = { _, _ -> },
    stopped: () -> Boolean,
  ): End? {
    val chunk = ByteArray(CHUNK_BYTES)
    try {
      while (!stopped()) {
        val count = read(chunk)
        // A read that returns nothing after stop() is the line draining, not a failure
        if (count <= 0) return if (stopped()) null else End.Device("no data from the input device")
        sink.write(chunk, 0, count)
        onLevel(rmsOf(chunk, count))
        onFrame(chunk, count)
        // The cap is enforced here rather than by a timer
        // A timer that fires while the line is blocked in read() stops nothing
        // And a microphone that stays on is the one failure mode of a recorder nobody forgives
        if (sink.size() >= capBytes) return End.Limit
      }
    }
    catch (e: Exception) {
      return if (stopped()) null else End.Device(reasonOf(e))
    }
    return null
  }

  /** PCM of [format] wrapped as WAV, in memory — the file the transcribers read, without the file. */
  fun wav(pcm: ByteArray, format: AudioFormat = format()): ByteArray {
    val out = ByteArrayOutputStream()
    AudioInputStream(ByteArrayInputStream(pcm), format, pcm.size.toLong() / format.frameSize).use {
      AudioSystem.write(it, AudioFileFormat.Type.WAVE, out)
    }
    return out.toByteArray()
  }

  /**
   * One recording in progress
   * Started by [start], ended by [stop], which says what was captured, or by itself, which the callback of [start] reports
   */
  interface Recording {
    val elapsedMs: Long

    /** Loudness of the latest chunk read from the device, 0 to 1 of full scale; 0 before the first one */
    val level: Double

    /**
     * What was captured so far, as WAV, while the recording goes on — the live transcript reads it.
     * Null while it is still too short to say anything, or while it holds only silence
     */
    fun snapshot(): ByteArray?

    /** Closes the microphone and says what came of the recording; blocks briefly, so never on the UI thread */
    fun stop(): Captured

    /** Throws away what was captured: the person changed their mind, and the file must not appear */
    fun cancel()
  }

  private class LineRecording(
    private val line: TargetDataLine,
    private val target: File,
    private val onEnded: (End) -> Unit,
    private val onFrame: (ByteArray, Int) -> Unit,
  ) : Recording {
    @Volatile private var stopped = false
    @Volatile private var latestLevel = 0.0
    private val buffer = ByteArrayOutputStream()
    private val thread = Thread({ pumpLoop() }, "vibe-voice-capture").apply { isDaemon = true; start() }

    override val elapsedMs: Long get() = ((buffer.size() / bytesPerMs()).toLong())
    override val level: Double get() = latestLevel

    private fun pumpLoop() {
      val end = pump({ line.read(it, 0, it.size) }, buffer, (MAX_MS * bytesPerMs()).toLong(), { latestLevel = it }, onFrame) { stopped }
      if (end != null) onEnded(end)
    }

    override fun snapshot(): ByteArray? {
      val bytes = buffer.toByteArray()
      return if (tooShort(bytes.size.toLong()) || isSilent(bytes)) null else wav(bytes)
    }

    override fun stop(): Captured {
      stopped = true
      runCatching { line.stop() }
      thread.join(JOIN_MS)
      runCatching { line.close() }
      val bytes = buffer.toByteArray()
      if (tooShort(bytes.size.toLong())) {
        runCatching { target.delete() }
        return Captured.TooShort
      }
      if (isSilent(bytes)) {
        runCatching { target.delete() }
        return Captured.Silence
      }
      return try {
        target.writeBytes(wav(bytes))
        Captured.Audio(target)
      }
      catch (e: java.io.IOException) {
        runCatching { target.delete() }
        Captured.Failed(reasonOf(e))
      }
    }

    override fun cancel() {
      stopped = true
      runCatching { line.stop() }
      runCatching { line.close() }
      thread.join(JOIN_MS)
      buffer.reset()
      runCatching { target.delete() }
    }
  }

  /**
   * Opens the microphone, and throws when the system refuses: no input device, or the line is taken
   *
   * Blocks while the system asks the person for permission, because macOS shows its dialog from inside the open call
   * So it must never run on the UI thread
   *
   * @param onFrame hears every chunk read from the device, on the thread that reads it; it must not keep the array
   */
  fun start(target: File, onEnded: (End) -> Unit = {}, onFrame: (ByteArray, Int) -> Unit = { _, _ -> }): Recording {
    val format = format()
    val line = AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine
    line.open(format)
    line.start()
    return LineRecording(line, target, onEnded, onFrame)
  }

  private const val CHUNK_BYTES = 4096
  private const val JOIN_MS = 2000L
}
