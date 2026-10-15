// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the microphone button means at each moment — the part of a dictation that needs no microphone to check. */
class VoiceFlowTest {
  @Test
  fun `a click means the next thing a person would expect in every state`() {
    assertEquals(VoiceFlow.Click.OPEN, VoiceFlow.onClick(VoiceFlow.State.IDLE))
    assertEquals(VoiceFlow.Click.CANCEL_OPEN, VoiceFlow.onClick(VoiceFlow.State.OPENING))
    assertEquals(VoiceFlow.Click.STOP, VoiceFlow.onClick(VoiceFlow.State.RECORDING))
    assertEquals(VoiceFlow.Click.OPEN, VoiceFlow.onClick(VoiceFlow.State.DONE), "the check mark is idle with a picture")
  }

  @Test
  fun `a click while the text is made cancels nothing`() {
    assertEquals(VoiceFlow.Click.NONE, VoiceFlow.onClick(VoiceFlow.State.TRANSCRIBING))
    assertEquals(VoiceFlow.Click.NONE, VoiceFlow.onClick(VoiceFlow.State.DOWNLOADING), "nor a click while the models download")
    assertTrue(VoiceFlow.Click.entries.none { it.name.contains("TRANSCRIBE") }, "there is no click that cancels a transcription")
  }

  @Test
  fun `only an idle or finished button opens a device, so a second click cannot start a second recording`() {
    val opening = VoiceFlow.State.entries.filter { VoiceFlow.onClick(it) == VoiceFlow.Click.OPEN }
    assertEquals(listOf(VoiceFlow.State.IDLE, VoiceFlow.State.DONE), opening)
  }

  @Test
  fun `giving up is possible exactly while there is something to give up`() {
    val cancellable = VoiceFlow.State.entries.filter { VoiceFlow.cancellable(it) }
    assertEquals(
      listOf(VoiceFlow.State.OPENING, VoiceFlow.State.RECORDING, VoiceFlow.State.TRANSCRIBING, VoiceFlow.State.DOWNLOADING),
      cancellable,
    )
  }

  @Test
  fun `every state has a tooltip of its own, so the button says what a click will do`() {
    val tips = VoiceFlow.State.entries.map { VoiceFlow.tooltip(it) }
    assertEquals(tips.size, tips.toSet().size, tips.toString())
    assertTrue(tips.none { it.isBlank() })
  }

  @Test
  fun `the tooltip of the transcription says that the key cancels it and the click does not`() {
    val tip = VoiceFlow.tooltip(VoiceFlow.State.TRANSCRIBING)
    assertTrue("Esc" in tip, tip)
    assertTrue("не отменяет" in tip, tip)
    assertTrue("Esc" in VoiceFlow.tooltip(VoiceFlow.State.RECORDING), "the recording tells how to drop it too")
  }

  @Test
  fun `the line under the input names every state a person waits in`() {
    val opening = assertNotNull(VoiceFlow.status(VoiceFlow.State.OPENING))
    assertTrue("микрофон" in opening, opening)
    assertTrue("разрешени" in opening, "the system may be asking for permission, and the line must say so: $opening")
    val recording = assertNotNull(VoiceFlow.status(VoiceFlow.State.RECORDING))
    assertTrue("записываю" in recording, recording)
    val transcribing = assertNotNull(VoiceFlow.status(VoiceFlow.State.TRANSCRIBING))
    assertTrue("расшифровываю" in transcribing && "Esc" in transcribing, transcribing)
    val done = assertNotNull(VoiceFlow.status(VoiceFlow.State.DONE))
    assertTrue("готово" in done, done)
    listOf(opening, recording, transcribing, done).forEach { assertTrue(it.startsWith("🎙"), "the same form as the live transcript: $it") }
  }

  @Test
  fun `the line is empty when nothing is going on`() {
    assertNull(VoiceFlow.status(VoiceFlow.State.IDLE))
  }

  @Test
  fun `an engine's wait hint comes with the way out`() {
    val line = VoiceFlow.waiting("модель скачивается")
    assertTrue("модель скачивается" in line && "Esc" in line, line)
  }

  @Test
  fun `the microphone is red only while it really records`() {
    assertEquals(listOf(VoiceFlow.State.RECORDING), VoiceFlow.State.entries.filter { VoiceFlow.micOn(it) })
    assertFalse(VoiceFlow.micOn(VoiceFlow.State.OPENING), "a device that is not open yet is not a recording")
  }

  @Test
  fun `the button turns while the person waits for something with no picture of its own`() {
    assertEquals(
      listOf(VoiceFlow.State.OPENING, VoiceFlow.State.TRANSCRIBING, VoiceFlow.State.DOWNLOADING),
      VoiceFlow.State.entries.filter { VoiceFlow.spinning(it) },
    )
  }

  @Test
  fun `no two states look alike, each is the spinner, the red microphone, the check or the grey one`() {
    val looks = VoiceFlow.State.entries.groupBy {
      when {
        VoiceFlow.spinning(it) -> "spinner"
        VoiceFlow.micOn(it) -> "red"
        it == VoiceFlow.State.DONE -> "check"
        else -> "grey"
      }
    }
    assertEquals(setOf("spinner", "red", "check", "grey"), looks.keys)
    assertEquals(listOf(VoiceFlow.State.IDLE), looks.getValue("grey"))
  }

  @Test
  fun `the check mark stays for a moment, not for good`() {
    assertTrue(VoiceFlow.DONE_SHOW_MS in 1_000L..3_000L, VoiceFlow.DONE_SHOW_MS.toString())
  }
}
