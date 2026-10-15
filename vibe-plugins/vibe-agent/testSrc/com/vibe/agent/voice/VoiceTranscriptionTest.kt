// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Two transcribers answer to «whisper» and want different command lines. */
class VoiceTranscriptionTest {
  private val cli = "/opt/homebrew/bin/whisper-cli"
  private val python = "/opt/homebrew/bin/whisper"
  private val model = "/models/ggml-base-q5_1.bin"
  private val hasModel: (String) -> Boolean = { it == model }

  private fun lookup(vararg present: String): (String) -> String? = { name -> present.firstOrNull { it.endsWith("/$name") } }

  @Test
  fun `whisper-cpp gets its own flags, a file prefix and auto language when none is set`() {
    val cmd = VoiceTranscription.command(
      VoiceTranscription.Transcriber(cli, VoiceTranscription.Kind.WHISPER_CPP, model), File("/tmp/a/note.wav"), File("/tmp/a"), " ")
    assertEquals(listOf(cli, "-m", model, "-f", "/tmp/a/note.wav", "-otxt", "-of", "/tmp/a/note", "-np", "-nt", "-l", "auto"), cmd)
    assertEquals(File("/tmp/a/note.txt"), VoiceTranscription.outputFile(File("/tmp/a/note.wav"), File("/tmp/a")))
    assertTrue("--output_format" !in cmd, "флаги openai-whisper whisper-cli не понимает и печатает справку")
  }

  @Test
  fun `a telegram note goes to openai-whisper first, whisper-cpp is the fallback`() {
    // Opus in OGG: openai-whisper decodes it through ffmpeg.
    assertEquals(VoiceTranscription.Kind.OPENAI_WHISPER,
                 VoiceTranscription.find(model, lookup = lookup(cli, python), hasFile = hasModel)?.kind)
    assertEquals(VoiceTranscription.Kind.WHISPER_CPP,
                 VoiceTranscription.find(model, lookup = lookup(cli), hasFile = hasModel)?.kind)
  }

  @Test
  fun `whisper-cpp without a model file set cannot be the fallback`() {
    assertNull(VoiceTranscription.find("", lookup = lookup(cli), hasFile = hasModel))
    assertNull(VoiceTranscription.find("/missing.bin", lookup = lookup(cli), hasFile = hasModel))
    assertNull(VoiceTranscription.find(model, lookup = lookup(), hasFile = hasModel))
  }
}
