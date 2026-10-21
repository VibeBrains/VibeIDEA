// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.watch

import com.vibe.agent.voice.VoiceEngine
import com.vibe.agent.voice.VoiceProblem
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the tool hands the model, and what counts as speech from a local engine. */
class WatchReportTest {
  private val frames = listOf(Path.of("/tmp/w/frame0001.jpg"), Path.of("/tmp/w/frame0002.jpg"))

  @Test
  fun `frames are listed with their time and the question comes last`() {
    val result = WatchPipeline.Result(WatchInput.Kind.VIDEO, "демо", "[0:00] привет", frames, listOf(0.0, 252.0), null)
    val report = WatchPrompt.toolReport(result, frames, "что показывают?")
    assertTrue("[0:00] /tmp/w/frame0001.jpg" in report, report)
    assertTrue("[4:12] /tmp/w/frame0002.jpg" in report, report)
    assertTrue("[0:00] привет" in report, report)
    assertTrue(report.trimEnd().endsWith("Вопрос: что показывают?"), report)
  }

  @Test
  fun `no transcript is said plainly so the model invents no lines`() {
    val result = WatchPipeline.Result(WatchInput.Kind.VIDEO, "демо", "", frames, emptyList(), "субтитров нет")
    val report = WatchPrompt.toolReport(result, frames, "")
    assertTrue("Транскрипта нет" in report, report)
    assertTrue("Предупреждение: субтитров нет" in report, report)
  }

  @Test
  fun `audio has no frame section`() {
    val result = WatchPipeline.Result(WatchInput.Kind.AUDIO, "подкаст", "[0:00] текст", emptyList(), emptyList(), null)
    assertTrue("Кадры" !in WatchPrompt.toolReport(result, emptyList(), "о чём"))
  }

  @Test
  fun `only finished text is speech`() {
    assertEquals("привет", WatchLocalSpeech.textOf(VoiceEngine.Outcome.Text("  привет \n")))
    assertNull(WatchLocalSpeech.textOf(VoiceEngine.Outcome.Text("  ")))
    assertNull(WatchLocalSpeech.textOf(VoiceEngine.Outcome.Failed(VoiceProblem.NoTranscriber)))
    assertNull(WatchLocalSpeech.textOf(VoiceEngine.Outcome.Cancelled))
  }
}
