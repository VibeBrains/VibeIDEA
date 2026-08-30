// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.vibe.agent.i18n.VibeI18n.t
import com.intellij.openapi.options.Configurable
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel

/** Settings → Tools → VibeIDEA → Чат. */
class VibeChatConfigurable : Configurable {
  private var continueField: JBTextField? = null
  private var tabsSpinner: JBIntSpinner? = null
  private var messagesSpinner: JBIntSpinner? = null
  private var sessionSpinner: JBIntSpinner? = null
  private var soundEnabled: com.intellij.ui.components.JBCheckBox? = null
  private var soundFinished: com.intellij.ui.components.JBCheckBox? = null
  private var soundStopped: com.intellij.ui.components.JBCheckBox? = null
  private var soundPermission: com.intellij.ui.components.JBCheckBox? = null
  private var soundMuteFocused: com.intellij.ui.components.JBCheckBox? = null
  private var soundVolume: JBIntSpinner? = null
  private var soundPath: JBTextField? = null
  private var codeFold: JBIntSpinner? = null

  override fun getDisplayName(): String = t("settings.chat.title")

  /** Grey hint under a control — one place, so wording and colour cannot drift between pages. */
  private fun hint(text: String) = JBLabel("<html>$text</html>").apply { foreground = com.intellij.ui.JBColor.GRAY }

  override fun createComponent(): JComponent {
    val field = JBTextField(VibeChatSettings.continueText, 24)
    continueField = field
    val tabs = JBIntSpinner(VibeChatSettings.maxOpenTabs, VibeChatSettings.MIN_OPEN_TABS, VibeChatSettings.MAX_OPEN_TABS)
    tabsSpinner = tabs
    val messages = JBIntSpinner(VibeChatSettings.maxMessagesPerThread, VibeChatSettings.MIN_MESSAGES_PER_THREAD, VibeChatSettings.MAX_MESSAGES_PER_THREAD)
    messagesSpinner = messages
    val sessionTokens = JBIntSpinner(VibeChatSettings.sessionTokenLimit.toInt(), 0, MAX_SESSION_TOKENS)
    sessionSpinner = sessionTokens
    return FormBuilder.createFormBuilder()
      .addLabeledComponent(t("settings.chat.continueText"), field)
      .addComponent(hint(t("settings.chat.continueHint", "default" to VibeChatSettings.DEFAULT_CONTINUE_TEXT)))
      .addLabeledComponent(t("settings.chat.maxTabs"), tabs)
      .addComponent(hint(t("settings.chat.maxTabsHint")))
      .addLabeledComponent(t("settings.chat.maxMessages"), messages)
      .addComponent(hint(t("settings.chat.maxMessagesHint")))
      .addLabeledComponent(t("settings.chat.sessionTokens"), sessionTokens)
      .addComponent(hint(t("settings.chat.sessionTokensHint")))
      .addLabeledComponent(t("settings.chat.codeFold"), JBIntSpinner(VibeChatSettings.codeFoldLines, 0, VibeChatSettings.MAX_CODE_FOLD_LINES).also { codeFold = it })
      .addComponent(hint(t("settings.chat.codeFoldHint")))
      .addComponent(com.intellij.ui.components.JBCheckBox(t("settings.sound.enabled"), VibeChatSettings.soundEnabled).also { soundEnabled = it })
      .addComponent(com.intellij.ui.components.JBCheckBox(t("settings.sound.onFinished"), VibeChatSettings.soundOnTurnFinished).also { soundFinished = it })
      .addComponent(com.intellij.ui.components.JBCheckBox(t("settings.sound.onStopped"), VibeChatSettings.soundOnTurnStopped).also { soundStopped = it })
      .addComponent(com.intellij.ui.components.JBCheckBox(t("settings.sound.onPermission"), VibeChatSettings.soundOnAwaitingPermission).also { soundPermission = it })
      .addComponent(com.intellij.ui.components.JBCheckBox(t("settings.sound.muteWhenFocused"), VibeChatSettings.soundMuteWhenFocused).also { soundMuteFocused = it })
      .addLabeledComponent(t("settings.sound.volume"), JBIntSpinner(VibeChatSettings.soundVolume, 0, 100).also { soundVolume = it })
      .addLabeledComponent(t("settings.sound.customPath"), JBTextField(VibeChatSettings.soundCustomPath, 24).also { soundPath = it })
      .addComponent(com.intellij.ui.components.ActionLink(t("settings.sound.preview")) { previewSound() })
      .addComponent(hint(t("settings.sound.hint")))
      .addComponentFillVertically(JPanel(), 0)
      .panel.apply { border = JBUI.Borders.empty(8) }
  }

  /**
   * Plays the sound the way the settings describe it — checking a custom file BEFORE saving, so a
   * file the JDK cannot read is named as such instead of silently producing nothing.
   */
  private fun previewSound() {
    val path = soundPath?.text?.trim().orEmpty()
    if (path.isNotEmpty()) {
      com.vibe.agent.sound.VibeSoundService.canPlay(path).onFailure {
        com.intellij.openapi.ui.Messages.showWarningDialog(
          t("settings.sound.cannotPlay", "reason" to it.message), t("settings.sound.section"))
        return
      }
    }
    VibeChatSettings.soundCustomPath = path
    VibeChatSettings.soundVolume = soundVolume?.number ?: VibeChatSettings.soundVolume
    com.vibe.agent.sound.VibeSoundService.getInstance().preview().onFailure {
      com.intellij.openapi.ui.Messages.showWarningDialog(
        t("settings.sound.previewFailed", "reason" to it.message), t("settings.sound.section"))
    }
  }

  override fun isModified(): Boolean =
    codeFold?.number != VibeChatSettings.codeFoldLines ||
    soundEnabled?.isSelected != VibeChatSettings.soundEnabled ||
    soundFinished?.isSelected != VibeChatSettings.soundOnTurnFinished ||
    soundStopped?.isSelected != VibeChatSettings.soundOnTurnStopped ||
    soundPermission?.isSelected != VibeChatSettings.soundOnAwaitingPermission ||
    soundMuteFocused?.isSelected != VibeChatSettings.soundMuteWhenFocused ||
    soundVolume?.number != VibeChatSettings.soundVolume ||
    (soundPath?.text?.trim() ?: VibeChatSettings.soundCustomPath) != VibeChatSettings.soundCustomPath ||
    (continueField?.text?.trim() ?: VibeChatSettings.continueText) != VibeChatSettings.continueText ||
    (tabsSpinner?.number ?: VibeChatSettings.maxOpenTabs) != VibeChatSettings.maxOpenTabs ||
    (messagesSpinner?.number ?: VibeChatSettings.maxMessagesPerThread) != VibeChatSettings.maxMessagesPerThread ||
    (sessionSpinner?.number?.toLong() ?: VibeChatSettings.sessionTokenLimit) != VibeChatSettings.sessionTokenLimit

  override fun apply() {
    codeFold?.let { VibeChatSettings.codeFoldLines = it.number }
    soundEnabled?.let { VibeChatSettings.soundEnabled = it.isSelected }
    soundFinished?.let { VibeChatSettings.soundOnTurnFinished = it.isSelected }
    soundStopped?.let { VibeChatSettings.soundOnTurnStopped = it.isSelected }
    soundPermission?.let { VibeChatSettings.soundOnAwaitingPermission = it.isSelected }
    soundMuteFocused?.let { VibeChatSettings.soundMuteWhenFocused = it.isSelected }
    soundVolume?.let { VibeChatSettings.soundVolume = it.number }
    soundPath?.let { VibeChatSettings.soundCustomPath = it.text }
    VibeChatSettings.continueText = continueField?.text ?: return
    continueField?.text = VibeChatSettings.continueText
    tabsSpinner?.let { VibeChatSettings.maxOpenTabs = it.number }
    messagesSpinner?.let { VibeChatSettings.maxMessagesPerThread = it.number }
    sessionSpinner?.let { VibeChatSettings.sessionTokenLimit = it.number.toLong() }
  }

  override fun reset() {
    codeFold?.number = VibeChatSettings.codeFoldLines
    soundEnabled?.isSelected = VibeChatSettings.soundEnabled
    soundFinished?.isSelected = VibeChatSettings.soundOnTurnFinished
    soundStopped?.isSelected = VibeChatSettings.soundOnTurnStopped
    soundPermission?.isSelected = VibeChatSettings.soundOnAwaitingPermission
    soundMuteFocused?.isSelected = VibeChatSettings.soundMuteWhenFocused
    soundVolume?.number = VibeChatSettings.soundVolume
    soundPath?.text = VibeChatSettings.soundCustomPath
    continueField?.text = VibeChatSettings.continueText
    tabsSpinner?.number = VibeChatSettings.maxOpenTabs
    messagesSpinner?.number = VibeChatSettings.maxMessagesPerThread
    sessionSpinner?.number = VibeChatSettings.sessionTokenLimit.toInt()
  }

  private companion object {
    /** Two hundred million tokens is far past any real chat — the spinner needs a top, not a policy. */
    const val MAX_SESSION_TOKENS = 200_000_000
  }
}
