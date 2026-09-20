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
    val installed = manager.installedThemes.associateBy { it.id }
    // Половина пары считается сломанной, если указывает на тему с перезапуском ИЛИ на тему, которой
    // в этой сборке больше нет: и то и другое платформа применит молча, а человек увидит
    // половинчатый интерфейс или чужую тему.
    fun broken(id: String?): Boolean = id != null && (installed[id] == null || !ThemeTargeting.switchable(id))
    val action = ThemePairDefault.decide(
      alreadySeeded = properties.getBoolean(KEY_SEEDED, false),
      lightSet = manager.preferredLightThemeId != null,
      darkSet = manager.preferredDarkThemeId != null,
      pairNeedsRestart = broken(manager.preferredLightThemeId) || broken(manager.preferredDarkThemeId),
    )
    // Отметку ставим в любом случае: «мы уже приходили» — факт, а не следствие того, что засеяли.
    properties.setValue(KEY_SEEDED, true)
    if (action == ThemePairDefault.Action.LEAVE_ALONE) return
    // Темы может не оказаться на месте — например, наш плагин тем отключили. Тогда не засеваем
    // ничего: половина пары хуже, чем её отсутствие, потому что выглядит как выбор.
    val light = installed[ThemePairDefault.LIGHT_ID] ?: return
    val dark = installed[ThemePairDefault.DARK_ID] ?: return
    ApplicationManager.getApplication().invokeLater {
      // При починке меняем только сломанную половину: вторая — выбор человека, и трогать её не за что.
      // Чиним сломанную половину И заполняем пустую: пара с одной половиной не работает вовсе,
      // а «не задано» днём при единственной светлой теме в наборе — не выбор человека, а дырка
      // (владелец увидел ровно это на 0.6.25).
      if (action == ThemePairDefault.Action.SEED || broken(manager.preferredLightThemeId) ||
          manager.preferredLightThemeId == null) {
        manager.setPreferredLightLaf(light)
      }
      if (action == ThemePairDefault.Action.SEED || broken(manager.preferredDarkThemeId) ||
          manager.preferredDarkThemeId == null) {
        manager.setPreferredDarkLaf(dark)
      }
    }
  }

  private companion object {
    const val KEY_SEEDED = "vibe.ui.themePairSeeded"
  }
}
