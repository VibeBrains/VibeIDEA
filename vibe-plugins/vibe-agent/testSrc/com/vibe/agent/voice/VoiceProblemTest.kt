// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import com.vibe.agent.journal.JournalLevel
import com.vibe.agent.journal.SettingsPage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which words the person gets for each way a dictation can stop short — the choice made without a microphone. */
class VoiceProblemTest {
  private val everyProblem: List<VoiceProblem> = listOf(
    VoiceProblem.NoMicrophone("line busy"),
    VoiceProblem.Silence,
    VoiceProblem.TooShort,
    VoiceProblem.RecordingBroke("device gone"),
    VoiceProblem.SaveFailed("disk full"),
    VoiceProblem.LimitReached(5),
    VoiceProblem.NoModel,
    VoiceProblem.NoTranscriber,
    VoiceProblem.ServerFailed("exit 1", afterStop = false),
    VoiceProblem.ServerFailed("exit 1", afterStop = true),
    VoiceProblem.TranscriberFailed(3, "boom"),
    VoiceProblem.TranscriberNotStarted("no such file"),
    VoiceProblem.TimedOut(180),
    VoiceProblem.Empty,
    VoiceProblem.Discarded("да"),
    VoiceProblem.Cancelled,
  )

  @Test
  fun `every way to stop short has words, so none of them ends in silence`() {
    everyProblem.forEach { problem ->
      val notice = problem.notice()
      assertTrue(notice.text.isNotBlank(), "$problem has no words")
      assertTrue("{" !in notice.text, "$problem left a placeholder unfilled: ${notice.text}")
    }
  }

  @Test
  fun `only what the person has to act on is a warning`() {
    val informational = setOf(
      VoiceProblem.TooShort, VoiceProblem.LimitReached(5), VoiceProblem.Empty, VoiceProblem.Discarded("да"), VoiceProblem.Cancelled,
    )
    everyProblem.forEach { problem ->
      val expected = if (problem in informational) JournalLevel.INFO else JournalLevel.WARNING
      assertEquals(expected, problem.notice().level, problem.toString())
    }
  }

  @Test
  fun `a problem the settings fix points to the chat page, and the others do not`() {
    val withPage = setOf(VoiceProblem.NoModel, VoiceProblem.NoTranscriber, VoiceProblem.TimedOut(180))
    everyProblem.forEach { problem ->
      assertEquals(if (problem in withPage) SettingsPage.CHAT else null, problem.notice().page, problem.toString())
    }
  }

  @Test
  fun `the reason of a failure is in the words`() {
    assertTrue("line busy" in VoiceProblem.NoMicrophone("line busy").notice().text)
    assertTrue("device gone" in VoiceProblem.RecordingBroke("device gone").notice().text)
    assertTrue("disk full" in VoiceProblem.SaveFailed("disk full").notice().text)
    assertTrue("exit 1" in VoiceProblem.ServerFailed("exit 1", afterStop = true).notice().text)
    val failed = VoiceProblem.TranscriberFailed(3, "boom").notice().text
    assertTrue("3" in failed && "boom" in failed, failed)
    assertTrue("no such file" in VoiceProblem.TranscriberNotStarted("no such file").notice().text)
    assertTrue("180" in VoiceProblem.TimedOut(180).notice().text)
    assertTrue("5" in VoiceProblem.LimitReached(5).notice().text)
  }

  @Test
  fun `a recording of silence points to the permission of the system`() {
    val text = VoiceProblem.Silence.notice().text
    assertTrue("тишину" in text, text)
    assertTrue("Микрофон" in text, "the system setting to open is named: $text")
  }

  @Test
  fun `a timeout says where the time may have gone and what to do instead`() {
    val text = VoiceProblem.TimedOut(180).notice().text
    assertTrue("whisper.cpp" in text && "модел" in text, text)
  }

  @Test
  fun `the live and the final failure of the server read differently`() {
    assertNotEquals(
      VoiceProblem.ServerFailed("x", afterStop = false).notice().text,
      VoiceProblem.ServerFailed("x", afterStop = true).notice().text,
    )
  }

  @Test
  fun `the feed line carries the source label that the journal line leaves to its category`() {
    val notice = VoiceProblem.TooShort.notice()
    assertTrue(notice.feedText.startsWith("[голос] "), notice.feedText)
    assertTrue(notice.feedText.endsWith(notice.text))
    assertTrue("[" !in notice.text, "the journal line has a category and needs no label: ${notice.text}")
  }

  @Test
  fun `the limit is told in minutes of the real cap`() {
    assertEquals(VoiceProblem.LimitReached(VoiceCapture.MAX_MS / 60_000), VoiceProblem.limitReached())
  }

  @Test
  fun `a recording that is not good audio is a problem, and good audio goes on`() {
    assertEquals(VoiceProblem.TooShort, VoiceProblem.ofCapture(VoiceCapture.Captured.TooShort))
    assertEquals(VoiceProblem.Silence, VoiceProblem.ofCapture(VoiceCapture.Captured.Silence))
    assertEquals(VoiceProblem.SaveFailed("disk full"), VoiceProblem.ofCapture(VoiceCapture.Captured.Failed("disk full")))
    assertNull(VoiceProblem.ofCapture(VoiceCapture.Captured.Audio(File("/tmp/note.wav"))))
  }

  @Test
  fun `a run that left no text is a problem, a transcript or a cancel by the person is not`() {
    assertNull(VoiceProblem.ofRun(VoiceTranscription.Once.Text("привет")))
    assertNull(VoiceProblem.ofRun(VoiceTranscription.Once.Cancelled), "the person cancelled it and has been told")
    assertEquals(VoiceProblem.TranscriberFailed(0, "no ffmpeg"), VoiceProblem.ofRun(VoiceTranscription.Once.Failed(0, "no ffmpeg")))
    assertEquals(VoiceProblem.TranscriberNotStarted("nope"), VoiceProblem.ofRun(VoiceTranscription.Once.NotStarted("nope")))
    assertEquals(VoiceProblem.TimedOut(180), VoiceProblem.ofRun(VoiceTranscription.Once.TimedOut(180)))
  }

  @Test
  fun `a missing model is told apart from a missing program`() {
    assertEquals(VoiceProblem.NoModel, VoiceProblem.missingTranscriber("", needsModel = true))
    assertEquals(VoiceProblem.NoTranscriber, VoiceProblem.missingTranscriber("", needsModel = false))
  }

  @Test
  fun `a blank transcript is empty, noise and a cough are discarded with what was heard, a task passes`() {
    assertEquals(VoiceProblem.Empty, VoiceProblem.ofTranscript(""))
    assertEquals(VoiceProblem.Empty, VoiceProblem.ofTranscript("  \n "))
    assertEquals(VoiceProblem.Discarded("да"), VoiceProblem.ofTranscript(" да \n"))
    assertEquals(VoiceProblem.Discarded("Продолжение следует…"), VoiceProblem.ofTranscript("Продолжение следует…"))
    assertNull(VoiceProblem.ofTranscript("почини падающий тест"))
  }

  @Test
  fun `the message of a discarded transcript names the length below which a transcript is a cough`() {
    val text = VoiceProblem.Discarded("да").notice().text
    assertTrue("да" in text, text)
    assertTrue(VoiceTranscription.MIN_TASK_CHARS.toString() in text, text)
  }
}
