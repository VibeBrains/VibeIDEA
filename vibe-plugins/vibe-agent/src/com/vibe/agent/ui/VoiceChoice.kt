// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.journal.SettingsPage
import com.vibe.agent.settings.VibeSettingsPages
import com.vibe.agent.voice.VoiceDictation
import com.vibe.agent.voice.VoiceDictation.ModelsAnswer
import com.vibe.agent.voice.VoiceDictation.PythonAnswer
import com.vibe.agent.voice.VoiceEngines

/**
 * The questions asked before the microphone opens: whether whisper (Python) may download over a gigabyte, and whether the
 * models of local recognition may be downloaded
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

  /** The buttons of the offer of the models: the earlier engine is offered only when there is one that would take the dictation */
  enum class ModelsButton { DOWNLOAD, EARLIER, CANCEL }

  fun modelsButtons(offer: VoiceEngines.Choice.OfferModels): List<ModelsButton> =
    if (offer.fallback is VoiceEngines.Choice.Use) listOf(ModelsButton.DOWNLOAD, ModelsButton.EARLIER, ModelsButton.CANCEL)
    else listOf(ModelsButton.DOWNLOAD, ModelsButton.CANCEL)

  fun modelsAnswerOf(buttons: List<ModelsButton>, index: Int): ModelsAnswer = when (buttons.getOrNull(index)) {
    ModelsButton.DOWNLOAD -> ModelsAnswer.DOWNLOAD
    ModelsButton.EARLIER -> ModelsAnswer.EARLIER
    ModelsButton.CANCEL, null -> ModelsAnswer.CANCEL
  }

  /**
   * Asks for the download of the models of local recognition, with their exact size and the folder they go to; UI thread
   * The default is the cancel button here too: an Enter on the dialog must not start a download of hundreds of megabytes
   */
  fun askModels(project: Project, offer: VoiceEngines.Choice.OfferModels): ModelsAnswer {
    val buttons = modelsButtons(offer)
    val mb = VoiceDictation.megabytes(offer.bytes)
    val labels = buttons.map {
      when (it) {
        ModelsButton.DOWNLOAD -> t("chat.voice.models.download", "mb" to mb)
        ModelsButton.EARLIER -> t("chat.voice.models.earlier", "engine" to (offer.fallback as VoiceEngines.Choice.Use).engine.name)
        ModelsButton.CANCEL -> t("common.cancel")
      }
    }
    val message = t("chat.voice.models.message", "mb" to mb, "folder" to offer.store.root.toString()) +
                  if (ModelsButton.EARLIER in buttons) "\n\n" + t("chat.voice.models.messageEarlier") else ""
    val title = t("chat.voice.models.title")
    val cancel = buttons.indexOf(ModelsButton.CANCEL)
    val chosen = Messages.showDialog(project, message, title, labels.toTypedArray(), cancel, Messages.getQuestionIcon())
    return modelsAnswerOf(buttons, chosen)
  }
}
