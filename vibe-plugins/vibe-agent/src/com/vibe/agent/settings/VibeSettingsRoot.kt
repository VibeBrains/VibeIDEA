// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.settings

import com.vibe.agent.i18n.VibeI18n.t

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.vibe.agent.journal.SettingsPage
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

/**
 * The pages of the settings hub by the ids the chat journal stores: an entry of the journal opens the page it points to
 * One `when` for both uses, so a new page cannot be added without its configurable
 * A test holds every page to a registration in `plugin.xml`
 */
object VibeSettingsPages {
  fun configurable(page: SettingsPage): Class<out Configurable> = when (page) {
    SettingsPage.PROVIDERS -> VibeProvidersConfigurable::class.java
    SettingsPage.MODELS -> VibeModelsConfigurable::class.java
    SettingsPage.AGENT -> VibeAgentConfigurable::class.java
    SettingsPage.CHAT -> VibeChatConfigurable::class.java
    SettingsPage.INTERFACE -> VibeUiConfigurable::class.java
    SettingsPage.APPEARANCE -> VibeAppearanceConfigurable::class.java
  }

  fun open(project: Project, page: SettingsPage) {
    ShowSettingsUtil.getInstance().showSettingsDialog(project, configurable(page))
  }
}
