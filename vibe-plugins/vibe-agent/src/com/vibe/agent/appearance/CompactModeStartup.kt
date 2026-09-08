// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.appearance

import com.intellij.ide.ui.UISettings
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Применяет умолчание плотности один раз на профиль настроек. См. [CompactModeDefault]. */
class CompactModeStartup : ProjectActivity {
  override suspend fun execute(project: Project) {
    // Ширина скролла в редакторе живёт в UI-свойстве темы, а не в нашем UI: ставим её при старте
    // тем же значением, что и остальные скроллы. Разбор — [com.vibe.agent.ui.EditorScrollBarWidth].
    com.vibe.agent.ui.EditorScrollBarWidth.apply()
    val properties = PropertiesComponent.getInstance()
    val settings = UISettings.getInstance()
    val action = CompactModeDefault.decide(
      alreadyApplied = properties.getBoolean(KEY_APPLIED, false),
      compactAlready = settings.compactMode,
    )
    // Отметку ставим в любом случае: «мы уже приходили» — факт, а не следствие того, что включили.
    properties.setValue(KEY_APPLIED, true)
    if (action != CompactModeDefault.Action.ENABLE) return
    ApplicationManager.getApplication().invokeLater {
      settings.compactMode = true
      // Без этого плотность применится только к тому, что нарисуется после следующего перезапуска.
      settings.fireUISettingsChanged()
    }
  }

  private companion object {
    const val KEY_APPLIED = "vibe.ui.compactModeDefaultApplied"
  }
}
