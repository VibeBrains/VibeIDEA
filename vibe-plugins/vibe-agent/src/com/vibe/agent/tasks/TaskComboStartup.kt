// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.tasks

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.tasks.config.TaskSettings

/**
 * Shows the task combo in every project, see [TaskComboDefault].
 * Registered through the optional dependency on the tasks plugin (vibe-tasks.xml): without the plugin there is nothing to show.
 */
class TaskComboStartup : ProjectActivity {
  override suspend fun execute(project: Project) {
    val properties = PropertiesComponent.getInstance()
    val settings = TaskSettings.getInstance()
    val action = TaskComboDefault.decide(
      alreadyApplied = properties.getBoolean(KEY_APPLIED, false),
      alwaysDisplayAlready = settings.ALWAYS_DISPLAY_COMBO,
    )
    // The mark is set either way: "we have been here" is a fact, not a consequence of switching it on.
    properties.setValue(KEY_APPLIED, true)
    if (action == TaskComboDefault.Action.ENABLE) settings.ALWAYS_DISPLAY_COMBO = true
  }

  private companion object {
    const val KEY_APPLIED = "vibe.tasks.comboDefaultApplied"
  }
}
