// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.vibe.agent.i18n.VibeI18n.t

import com.intellij.openapi.options.Configurable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel

/** Root of the VibeIDEA settings hub (children: providers, models). */
// NoScroll обязателен: без него платформа заворачивает нашу страницу-со-скроллом во ВТОРОЙ скролл
// и выдаёт ей всю предпочтительную ширину — наш скролл тогда ничего не решает, и содержимое уезжает
// за край диалога (ConfigurableCardPanel.createConfigurableComponent; владелец, 20.09.2026).
class VibeSettingsRoot : Configurable, Configurable.NoScroll {
  override fun getDisplayName(): String = "VibeIDEA"
  /**
   * Прокрутка только вертикальная: страница настроек, которая едет вбок, — дефект, а не мелочь.
   * Правило и способ — docs/vibe/knowledge/ui/settingsPageWidth.md.
   */
  override fun createComponent(): JComponent = SettingsUi.page(
    FormBuilder.createFormBuilder()
      .addComponent(SettingsUi.hint(t("settings.root.html")))
      .addComponentFillVertically(JPanel(), 0)
      .panel.apply { border = JBUI.Borders.empty(8) }
  )
  override fun isModified(): Boolean = false
  override fun apply() {}
}
