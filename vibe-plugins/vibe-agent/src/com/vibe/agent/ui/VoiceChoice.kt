// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.journal.SettingsPage
import com.vibe.agent.settings.VibeSettingsPages
import com.vibe.agent.voice.VoiceDictation.PythonAnswer

/**
 * The question asked when the only tool for the microphone is one that downloads over a gigabyte
 *
 * A dialog and not a line in the feed: the recording does not start until the person has answered, and a question
 * that can be scrolled past is a question that is answered by the download
 * The default is the cancel button, so that a key pressed on the dialog never agrees to the download
 */
object VoiceChoice {
  /** The buttons in the order they are shown: setting a model is offered only when whisper.cpp is there to use it */
  enum class Button { MODEL, PYTHON, CANCEL }

  fun buttons(cliInstalled: Boolean): List<Button> =
    if (cliInstalled) listOf(Button.MODEL, Button.PYTHON, Button.CANCEL) else listOf(Button.PYTHON, Button.CANCEL)

  /** The button an Enter press lands on: the one that agrees to nothing */
  fun defaultIndex(buttons: List<Button>): Int = buttons.indexOf(Button.CANCEL)

  /** What the pressed button means; a dialog closed with its cross has no index and means no */
  fun answerOf(buttons: List<Button>, index: Int): PythonAnswer = when (buttons.getOrNull(index)) {
    Button.MODEL -> PythonAnswer.SETTINGS
    Button.PYTHON -> PythonAnswer.USE
    Button.CANCEL, null -> PythonAnswer.CANCEL
  }

  /** Asks, and for a model opens the chat settings before returning; UI thread */
  fun ask(project: Project, cliInstalled: Boolean): PythonAnswer {
    val buttons = buttons(cliInstalled)
    val labels = buttons.map {
      when (it) {
        Button.MODEL -> t("chat.voice.python.useModel")
        Button.PYTHON -> t("chat.voice.python.usePython")
        Button.CANCEL -> t("common.cancel")
      }
    }
    val message = if (cliInstalled) t("chat.voice.python.message") else t("chat.voice.python.messageNoCli")
    val chosen = Messages.showDialog(
      project, message, t("chat.voice.python.title"), labels.toTypedArray(), defaultIndex(buttons), Messages.getQuestionIcon(),
    )
    val answer = answerOf(buttons, chosen)
    if (answer == PythonAnswer.SETTINGS) VibeSettingsPages.open(project, SettingsPage.CHAT)
    return answer
  }
}
