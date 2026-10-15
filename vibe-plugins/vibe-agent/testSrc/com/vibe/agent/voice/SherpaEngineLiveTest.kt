// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real sherpa-onnx with the real Russian models, through the code of the plugin — only where all of it is named:
 * `--test_env=VIBE_SHERPA_NATIVE=<folder with libonnxruntime and libsherpa-onnx-jni>`,
 * `--test_env=VIBE_STT_MODELS=<folder the models are unpacked in>` and
 * `--test_env=VIBE_VOICE_SAMPLE_RU=<16 kHz mono WAV>` with the Russian words for "voice input" spoken in it
 * Elsewhere it is skipped: a unit suite must not depend on native libraries and three hundred megabytes of models
 */
class SherpaEngineLiveTest {
  @Test
  fun `the Russian models make text of a recording, live phrase by phrase and as a file`() {
    val models = System.getenv("VIBE_STT_MODELS")?.takeIf { File(it).isDirectory }
    val sample = System.getenv("VIBE_VOICE_SAMPLE_RU")?.let { File(it) }?.takeIf { it.isFile }
    assumeTrue(System.getenv(SherpaNative.DIR_ENV) != null && models != null && sample != null, "the live inputs are not set")
    assertEquals(SherpaNative.Status.Ready, SherpaNative.status())
    val profile = checkNotNull(SttCatalog.bundled?.profileFor("ru"))
    val store = SttModelStore(Path.of(models!!))
    assumeTrue(store.missing(profile).isEmpty(), "the Russian models are not in $models")
    val engine = SherpaEngine(profile, store) { KEEP_LOADED_MS }
    val wav = sample!!.readBytes()

    val drafts = mutableListOf<String>()
    val phrases = mutableListOf<String>()
    val listenStarted = System.nanoTime()
    val listening = engine.listen({ text ->
      when (text) {
        is VoiceEngine.LiveText.Draft -> drafts += text.text
        is VoiceEngine.LiveText.Phrase -> phrases += text.text
      }
    }, { problem -> error(problem.toString()) })
    // The bytes of the data chunk in the chunks the microphone reads, as the capture thread would feed them
    val pcm = pcmOf(wav)
    var offset = 0
    while (offset < pcm.size) {
      val count = minOf(FRAME_BYTES, pcm.size - offset)
      listening.feed(pcm.copyOfRange(offset, offset + count), count)
      offset += count
    }
    val live = listening.finish()
    val liveMs = (System.nanoTime() - listenStarted) / NANOS_PER_MS
    println("live (first load included): ${liveMs} ms, ${drafts.size} drafts, phrases=$phrases, outcome=$live")
    assertTrue(drafts.isNotEmpty(), "the draft is printed while the person speaks")
    val liveText = (live as VoiceEngine.Outcome.Text).text
    assertTrue("голосовой ввод" in liveText, liveText)

    val fileStarted = System.nanoTime()
    val file = engine.transcribe(sample, cancelled = { false }) { }
    val fileMs = (System.nanoTime() - fileStarted) / NANOS_PER_MS
    println("file (models resident): ${fileMs} ms, outcome=$file")
    assertTrue("голосовой ввод" in (file as VoiceEngine.Outcome.Text).text, file.text)
  }

  /** The samples of the data chunk as 16-bit little-endian bytes */
  private fun pcmOf(wav: ByteArray): ByteArray {
    val samples = checkNotNull(VoiceAudio.samplesOfWav(wav))
    val out = ByteArray(samples.size * 2)
    samples.forEachIndexed { i, sample ->
      val value = (sample * FULL_SCALE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
      out[2 * i] = (value and 0xFF).toByte()
      out[2 * i + 1] = (value shr 8 and 0xFF).toByte()
    }
    return out
  }

  private companion object {
    const val FRAME_BYTES = 4096
    const val KEEP_LOADED_MS = 60_000L
    const val NANOS_PER_MS = 1_000_000L
    const val FULL_SCALE = 32768f
  }
}
