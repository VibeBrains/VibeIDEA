// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

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

  override fun getDisplayName(): String = "Чат"

  override fun createComponent(): JComponent {
    val field = JBTextField(VibeChatSettings.continueText, 24)
    continueField = field
    val tabs = JBIntSpinner(VibeChatSettings.maxOpenTabs, VibeChatSettings.MIN_OPEN_TABS, VibeChatSettings.MAX_OPEN_TABS)
    tabsSpinner = tabs
    val messages = JBIntSpinner(VibeChatSettings.maxMessagesPerThread, VibeChatSettings.MIN_MESSAGES_PER_THREAD, VibeChatSettings.MAX_MESSAGES_PER_THREAD)
    messagesSpinner = messages
    return FormBuilder.createFormBuilder()
      .addLabeledComponent("Текст кнопки ▷ «быстрое продолжение»:", field)
      .addComponent(JBLabel("<html>Кнопка ▷ справа от поля ввода отправляет этот текст как ваше сообщение — подпинывает агента, " +
        "когда он остановился. Пусто — вернётся «${VibeChatSettings.DEFAULT_CONTINUE_TEXT}».</html>").apply {
        foreground = com.intellij.ui.JBColor.GRAY
      })
      .addLabeledComponent("Открытых вкладок чата (макс.):", tabs)
      .addComponent(JBLabel("<html>Сверх лимита тихо закрывается самая старая неактивная вкладка — тред остаётся в истории.</html>").apply {
        foreground = com.intellij.ui.JBColor.GRAY
      })
      .addLabeledComponent("Сообщений в треде (макс.):", messages)
      .addComponent(JBLabel("<html>При переполнении старые сообщения обрезаются с маркером в начале треда.</html>").apply {
        foreground = com.intellij.ui.JBColor.GRAY
      })
      .addComponentFillVertically(JPanel(), 0)
      .panel.apply { border = JBUI.Borders.empty(8) }
  }

  override fun isModified(): Boolean =
    (continueField?.text?.trim() ?: VibeChatSettings.continueText) != VibeChatSettings.continueText ||
    (tabsSpinner?.number ?: VibeChatSettings.maxOpenTabs) != VibeChatSettings.maxOpenTabs ||
    (messagesSpinner?.number ?: VibeChatSettings.maxMessagesPerThread) != VibeChatSettings.maxMessagesPerThread

  override fun apply() {
    VibeChatSettings.continueText = continueField?.text ?: return
    continueField?.text = VibeChatSettings.continueText
    tabsSpinner?.let { VibeChatSettings.maxOpenTabs = it.number }
    messagesSpinner?.let { VibeChatSettings.maxMessagesPerThread = it.number }
  }

  override fun reset() {
    continueField?.text = VibeChatSettings.continueText
    tabsSpinner?.number = VibeChatSettings.maxOpenTabs
    messagesSpinner?.number = VibeChatSettings.maxMessagesPerThread
  }
}
