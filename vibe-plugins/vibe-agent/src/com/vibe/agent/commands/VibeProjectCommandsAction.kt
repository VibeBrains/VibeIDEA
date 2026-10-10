// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.commands

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.vibe.agent.i18n.VibeI18n.t

/**
 * Every command of the project as one list, pinned or not; the chosen one runs through [ProjectCommandRunner]
 *
 * This is also where the file speaks up: entries that were refused are named in a dialog rather than
 * dropped, because a command lost in silence looks like a broken IDE.
 */
class VibeProjectCommandsAction : DumbAwareAction({ t("commands.action") }) {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project?.basePath != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val runner = ProjectCommandRunner.getInstance(project)
    val parsed = loadOrExplain(project) ?: return
    if (parsed.problems.isNotEmpty()) {
      Messages.showWarningDialog(project, t("commands.problems", "problems" to parsed.problems.joinToString(", ")), t("commands.title"))
    }
    if (parsed.commands.isEmpty()) return
    JBPopupFactory.getInstance()
      .createPopupChooserBuilder(parsed.commands)
      .setTitle(t("commands.title"))
      .setRenderer(com.intellij.ui.SimpleListCellRenderer.create("") { it.name + "  ·  " + it.line })
      .setNamerForFiltering { it.name + " " + it.line }
      .setItemChosenCallback { runner.run(it) }
      .createPopup()
      .showCenteredInCurrentWindow(project)
  }

  companion object {
    /** The parsed file, or null after telling the person that the project has none */
    fun loadOrExplain(project: Project): ProjectCommands.Parsed? =
      ProjectCommandRunner.getInstance(project).load() ?: run {
        Messages.showInfoMessage(project, t("commands.none", "path" to ProjectCommands.FILE), t("commands.title"))
        null
      }
  }
}
