// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.voice

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The real `whisper-server` with a real model — only where both exist and are named:
 * `--test_env=VIBE_WHISPER_MODEL=<ggml .bin>` and, for a phrase to recognise,
 * `--test_env=VIBE_VOICE_SAMPLE=<wav>` with «голос» spoken in it. Elsewhere it is skipped: a unit
 * suite must not depend on a model file of tens of megabytes being on the machine.
 */
class VoiceServerLiveTest {
  @Test
  fun `a resident server transcribes our capture and keeps the model loaded`() {
    val model = System.getenv("VIBE_WHISPER_MODEL")
    assumeTrue(model != null && File(model).isFile, "VIBE_WHISPER_MODEL is not set")
    val config = VoiceServer.configOf(model, "ru")
    assumeTrue(config != null, "whisper-server is not installed")
    val cfg = config!!
    val server = VoiceServer()
    try {
      val sample = System.getenv("VIBE_VOICE_SAMPLE")?.let { File(it) }?.takeIf { it.isFile }
      val wav = sample?.readBytes() ?: VoiceCapture.wav(ByteArray(32_000))
      val first = server.transcribe(wav, cfg, VoiceServer.FINAL_TIMEOUT_MS)
      assertNotNull(first, "сервер ответил, а текста в ответе нет")
      if (sample != null) assertTrue("голос" in first.lowercase(), first)
      // The second request goes to the same, already running server — no second model load.
      val started = System.nanoTime()
      server.transcribe(wav, cfg, VoiceServer.PREVIEW_TIMEOUT_MS)
      assertTrue(System.nanoTime() - started < VoiceServer.PREVIEW_TIMEOUT_MS * 1_000_000, "повторный запрос не должен ждать загрузки")
    }
    finally {
      server.stop()
    }
  }
}
