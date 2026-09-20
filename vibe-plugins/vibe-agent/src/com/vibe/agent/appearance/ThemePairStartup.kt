// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.appearance

import com.intellij.ide.ui.LafManager
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Заполняет пару «день/ночь» нашими темами один раз на профиль настроек. См. [ThemePairDefault]. */
class ThemePairStartup : ProjectActivity {
  override suspend fun execute(project: Project) {
    val properties = PropertiesComponent.getInstance()
    val manager = LafManager.getInstance()
    val action = ThemePairDefault.decide(
      alreadySeeded = properties.getBoolean(KEY_SEEDED, false),
      lightSet = manager.preferredLightThemeId != null,
      darkSet = manager.preferredDarkThemeId != null,
    )
    // Отметку ставим в любом случае: «мы уже приходили» — факт, а не следствие того, что засеяли.
    properties.setValue(KEY_SEEDED, true)
    if (action != ThemePairDefault.Action.SEED) return
    val installed = manager.installedThemes.associateBy { it.id }
    // Темы может не оказаться на месте — например, наш плагин тем отключили. Тогда не засеваем
    // ничего: половина пары хуже, чем её отсутствие, потому что выглядит как выбор.
    val light = installed[ThemePairDefault.LIGHT_ID] ?: return
    val dark = installed[ThemePairDefault.DARK_ID] ?: return
    ApplicationManager.getApplication().invokeLater {
      manager.setPreferredLightLaf(light)
      manager.setPreferredDarkLaf(dark)
    }
  }

  private companion object {
    const val KEY_SEEDED = "vibe.ui.themePairSeeded"
  }
}
