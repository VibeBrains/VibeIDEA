// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import com.vibe.agent.ui.VoiceChoice.Button
import com.vibe.agent.voice.VoiceDictation.PythonAnswer
import kotlin.test.Test
import kotlin.test.assertEquals

/** The question about whisper (Python): which buttons it has and what each of them agrees to. */
class VoiceChoiceTest {
  @Test
  fun `setting a model is offered only where whisper-cpp is installed to use it`() {
    assertEquals(listOf(Button.MODEL, Button.PYTHON, Button.CANCEL), VoiceChoice.buttons(cliInstalled = true))
    assertEquals(listOf(Button.PYTHON, Button.CANCEL), VoiceChoice.buttons(cliInstalled = false))
  }

  @Test
  fun `each button means what it says`() {
    val three = VoiceChoice.buttons(true)
    assertEquals(PythonAnswer.SETTINGS, VoiceChoice.answerOf(three, 0))
    assertEquals(PythonAnswer.USE, VoiceChoice.answerOf(three, 1))
    assertEquals(PythonAnswer.CANCEL, VoiceChoice.answerOf(three, 2))
    val two = VoiceChoice.buttons(false)
    assertEquals(PythonAnswer.USE, VoiceChoice.answerOf(two, 0))
    assertEquals(PythonAnswer.CANCEL, VoiceChoice.answerOf(two, 1))
  }

  @Test
  fun `a dialog closed with its cross, or with a key the dialog does not know, agrees to nothing`() {
    assertEquals(PythonAnswer.CANCEL, VoiceChoice.answerOf(VoiceChoice.buttons(true), -1))
    assertEquals(PythonAnswer.CANCEL, VoiceChoice.answerOf(VoiceChoice.buttons(true), 7))
  }

  @Test
  fun `only one button agrees to the download, and it is never the default`() {
    listOf(true, false).forEach { cli ->
      val buttons = VoiceChoice.buttons(cli)
      assertEquals(1, buttons.count { VoiceChoice.answerOf(buttons, buttons.indexOf(it)) == PythonAnswer.USE })
      assertEquals(PythonAnswer.CANCEL, VoiceChoice.answerOf(buttons, VoiceChoice.defaultIndex(buttons)), "a stray Enter agrees to nothing")
    }
  }
}
