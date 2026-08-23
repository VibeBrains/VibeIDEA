// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel

/** Settings → Tools → VibeIDEA → Чат. */
class VibeChatConfigurable : Configurable {
  private var continueField: JBTextField? = null

  override fun getDisplayName(): String = "Чат"

  override fun createComponent(): JComponent {
    val field = JBTextField(VibeChatSettings.continueText, 24)
    continueField = field
    return FormBuilder.createFormBuilder()
      .addLabeledComponent("Текст кнопки ▷ «быстрое продолжение»:", field)
      .addComponent(JBLabel("<html>Кнопка ▷ справа от поля ввода отправляет этот текст как ваше сообщение — подпинывает агента, " +
        "когда он остановился. Пусто — вернётся «${VibeChatSettings.DEFAULT_CONTINUE_TEXT}».</html>").apply {
        foreground = com.intellij.ui.JBColor.GRAY
      })
      .addComponentFillVertically(JPanel(), 0)
      .panel.apply { border = JBUI.Borders.empty(8) }
  }

  override fun isModified(): Boolean = (continueField?.text?.trim() ?: VibeChatSettings.continueText) != VibeChatSettings.continueText

  override fun apply() {
    VibeChatSettings.continueText = continueField?.text ?: return
    continueField?.text = VibeChatSettings.continueText
  }

  override fun reset() {
    continueField?.text = VibeChatSettings.continueText
  }
}
