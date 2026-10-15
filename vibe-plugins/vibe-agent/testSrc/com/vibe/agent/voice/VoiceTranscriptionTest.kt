// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Two transcribers answer to «whisper» and want different command lines. */
class VoiceTranscriptionTest {
  private val cli = "/opt/homebrew/bin/whisper-cli"
  private val model = "/models/ggml-base-q5_1.bin"

  @Test
  fun `whisper-cpp gets its own flags, a file prefix and auto language when none is set`() {
    val cmd = VoiceTranscription.command(
      VoiceTranscription.Transcriber(cli, VoiceTranscription.Kind.WHISPER_CPP, model), File("/tmp/a/note.wav"), File("/tmp/a"), " ")
    assertEquals(listOf(cli, "-m", model, "-f", "/tmp/a/note.wav", "-otxt", "-of", "/tmp/a/note", "-np", "-nt", "-l", "auto"), cmd)
    assertEquals(File("/tmp/a/note.txt"), VoiceTranscription.outputFile(File("/tmp/a/note.wav"), File("/tmp/a")))
    assertTrue("--output_format" !in cmd, "флаги openai-whisper whisper-cli не понимает и печатает справку")
  }
}
