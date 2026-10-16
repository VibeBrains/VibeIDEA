// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import org.junit.jupiter.api.Assumptions.assumeFalse
import com.vibe.agent.watch.WatchTools
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The one-shot run against stand-in programs: what it returns when the transcriber works, fails, hangs or is not there. */
class VoiceTranscriptionRunTest {
  private val dir: File = Files.createTempDirectory("vibe-voice-run-test").toFile()
  private val audio = File(dir, "note.wav").apply { writeBytes(ByteArray(8)) }

  /**
   * A stand-in with the command line of openai-whisper: `audio --output_format txt --output_dir DIR [--language L]`
   *
   * The transcriber is `/bin/sh` and the "audio" file is the script, so `$0` is the audio and `$4` the output directory
   * No new executable is created: macOS vets the first launch of every fresh executable (syspolicyd),
   * and on a loaded machine that took 10-26 s, past the run limit, on whichever test came first
   */
  private fun program(body: String): VoiceTranscription.Transcriber {
    assumeFalse(System.getProperty("os.name").startsWith("Windows"), "the stand-ins are shell scripts")
    audio.writeText("$body\n")
    return VoiceTranscription.Transcriber(SHELL)
  }

  private fun run(transcriber: VoiceTranscription.Transcriber, timeoutMs: Long = 20_000, cancelled: () -> Boolean = { false }) =
    try {
      VoiceTranscription.runOnce(transcriber, audio, dir, "ru", timeoutMs, cancelled)
    }
    finally {
      dir.deleteRecursively()
    }

  @Test
  fun `a transcriber that writes its transcript gives it back`() {
    val result = run(program("printf 'привет мир' > ${'$'}4/note.txt"))
    assertEquals(VoiceTranscription.Once.Text("привет мир"), result)
  }

  @Test
  fun `a transcriber that exits quietly without a transcript is a failure with the last thing it said`() {
    // openai-whisper without ffmpeg on PATH: it skips the file and still exits 0
    val result = run(program("echo \"Skipping ${'$'}0 due to FileNotFoundError: ffmpeg\" >&2"))
    assertTrue(result is VoiceTranscription.Once.Failed, result.toString())
    result as VoiceTranscription.Once.Failed
    assertEquals(0, result.exitCode)
    assertTrue("ffmpeg" in result.tail, result.tail)
  }

  @Test
  fun `a transcriber that fails keeps its exit code`() {
    val result = run(program("echo first; echo boom; exit 3"))
    assertEquals(VoiceTranscription.Once.Failed(3, "boom"), result)
  }

  @Test
  fun `a transcriber that hangs is stopped at the limit and does not hold the caller`() {
    val started = System.nanoTime()
    val result = run(program("sleep 30"), timeoutMs = 600)
    val tookMs = (System.nanoTime() - started) / 1_000_000
    assertEquals(VoiceTranscription.Once.TimedOut(0), result, "600 ms is below a whole second")
    assertTrue(tookMs < 10_000, "the sleep of 30 s must not be waited out: $tookMs ms")
  }

  @Test
  fun `a cancel by the person stops the run`() {
    val started = System.nanoTime()
    val result = run(program("sleep 30"), cancelled = { true })
    assertEquals(VoiceTranscription.Once.Cancelled, result)
    assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000)
  }

  @Test
  fun `a transcriber that is not there is not started, with the reason`() {
    val result = run(VoiceTranscription.Transcriber(File(dir, "no-such-program").absolutePath))
    assertTrue(result is VoiceTranscription.Once.NotStarted && result.reason.isNotBlank(), result.toString())
  }

  @Test
  fun `the transcriber finds the tools of a shell, not only those of a GUI launch`() {
    val result = run(program("printf '%s' \"${'$'}PATH\" > ${'$'}4/note.txt"))
    assertTrue(result is VoiceTranscription.Once.Text, result.toString())
    result as VoiceTranscription.Once.Text
    assertEquals(WatchTools.searchPath(), result.text)
  }

  @Test
  fun `the last line of the output is what is kept, whichever way it is cut into lines`() {
    assertEquals("20%|##", VoiceTranscription.tailOf("10%|#\r20%|##\r\n  \n"))
    assertEquals("", VoiceTranscription.tailOf(" \n\r"))
    assertEquals(240, VoiceTranscription.tailOf("x".repeat(1000)).length)
  }

  private companion object {
    const val SHELL = "/bin/sh"
  }
}
