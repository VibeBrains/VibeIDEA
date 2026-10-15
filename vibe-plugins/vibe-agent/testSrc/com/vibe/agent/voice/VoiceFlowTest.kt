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
    assertEquals(VoiceFlow.Click.CANCEL_TRANSCRIBE, VoiceFlow.onClick(VoiceFlow.State.TRANSCRIBING))
  }

  @Test
  fun `only an idle button opens a device, so a second click cannot start a second recording`() {
    val opening = VoiceFlow.State.entries.filter { VoiceFlow.onClick(it) == VoiceFlow.Click.OPEN }
    assertEquals(listOf(VoiceFlow.State.IDLE), opening)
  }

  @Test
  fun `every state has a tooltip of its own, so the button says what a click will do`() {
    val tips = VoiceFlow.State.entries.map { VoiceFlow.tooltip(it) }
    assertEquals(tips.size, tips.toSet().size, tips.toString())
    assertTrue(tips.none { it.isBlank() })
  }

  @Test
  fun `the line under the input names the wait while the device opens and the text is made`() {
    val opening = assertNotNull(VoiceFlow.status(VoiceFlow.State.OPENING))
    assertTrue("микрофон" in opening, opening)
    assertTrue("разрешени" in opening, "the system may be asking for permission, and the line must say so: $opening")
    val transcribing = assertNotNull(VoiceFlow.status(VoiceFlow.State.TRANSCRIBING))
    assertTrue("расшифровываю" in transcribing, transcribing)
    assertTrue(opening.startsWith("🎙") && transcribing.startsWith("🎙"), "the same form as the live transcript")
  }

  @Test
  fun `the line is empty when nothing is going on, and while recording it belongs to the live transcript`() {
    assertNull(VoiceFlow.status(VoiceFlow.State.IDLE))
    assertNull(VoiceFlow.status(VoiceFlow.State.RECORDING))
  }

  @Test
  fun `the microphone is red only while it really records`() {
    assertEquals(listOf(VoiceFlow.State.RECORDING), VoiceFlow.State.entries.filter { VoiceFlow.micOn(it) })
    assertFalse(VoiceFlow.micOn(VoiceFlow.State.OPENING), "a device that is not open yet is not a recording")
  }
}
