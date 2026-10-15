// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import com.vibe.agent.watch.WatchTools
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Audio in the shape the recognizers take: samples of [VoiceCapture.format], from the microphone or from a file
 *
 * The microphone already records that format; a note from Telegram is Opus in OGG, which the JDK cannot decode,
 * so it goes through `ffmpeg`, the one tool that every machine with a transcriber of the earlier kind already had
 */
object VoiceAudio {
  private const val FULL_SCALE = 32768f
  private const val RIFF_HEADER_BYTES = 12
  private const val CHUNK_HEADER_BYTES = 8
  private const val DATA_CHUNK = "data"
  private const val FFMPEG = "ffmpeg"
  private const val CONVERT_TIMEOUT_SEC = 120L
  private const val LOG_SUFFIX = ".log"

  /** The first [length] bytes of [pcm], 16-bit little-endian, as samples from -1 to 1 */
  fun floatsOf(pcm: ByteArray, length: Int = pcm.size): FloatArray {
    val count = length / 2
    val out = FloatArray(count)
    for (i in 0 until count) {
      val sample = (pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)
      out[i] = sample.toShort() / FULL_SCALE
    }
    return out
  }

  /**
   * The samples of a WAV of [VoiceCapture.format], or null when [wav] has no data chunk
   * The chunks are walked rather than a header of 44 bytes assumed: macOS tools write a padding chunk before the data
   */
  fun samplesOfWav(wav: ByteArray): FloatArray? {
    val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    var position = RIFF_HEADER_BYTES
    while (position + CHUNK_HEADER_BYTES <= wav.size) {
      val id = String(wav, position, 4, Charsets.US_ASCII)
      val size = buffer.getInt(position + 4)
      val start = position + CHUNK_HEADER_BYTES
      if (id == DATA_CHUNK) return floatsOf(wav.copyOfRange(start, minOf(wav.size, start + size)))
      if (size < 0) return null
      position = start + size + (size and 1)
    }
    return null
  }

  /**
   * [input] converted into a WAV of [VoiceCapture.format] at [output], through `ffmpeg`
   * Null when it worked; otherwise why not, in the words of the system
   */
  fun convert(input: File, output: File, lookup: (String) -> String? = WatchTools::find): String? {
    val ffmpeg = lookup(FFMPEG) ?: return "$FFMPEG not found"
    val command = listOf(
      ffmpeg, "-nostdin", "-y", "-loglevel", "error", "-i", input.absolutePath,
      "-ar", VoiceCapture.SAMPLE_RATE.toInt().toString(), "-ac", VoiceCapture.CHANNELS.toString(), "-c:a", "pcm_s16le", output.absolutePath,
    )
    // The output goes to a file and not a pipe: a pipe nobody drains while waiting can hold the process forever
    val log = File(output.parentFile, output.name + LOG_SUFFIX)
    return try {
      val process = VoiceTranscription.processOf(command).redirectOutput(log).start()
      if (!process.waitFor(CONVERT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        "$FFMPEG timed out"
      }
      else if (process.exitValue() != 0 || !output.isFile) {
        "$FFMPEG exit ${process.exitValue()}: ${VoiceTranscription.tailOf(log.readText())}"
      }
      else null
    }
    catch (e: IOException) {
      reasonOf(e)
    }
    finally {
      log.delete()
    }
  }

  /** Whether [convert] can run here at all */
  fun canConvert(lookup: (String) -> String? = WatchTools::find): Boolean = lookup(FFMPEG) != null
}
