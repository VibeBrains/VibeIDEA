// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graph

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.vibe.agent.i18n.VibeI18n.t

/**
 * Brain menu → Project → "Project graph": opens the picture of the code graph in the centre
 *
 * One tab, not one per press: the graph holds no state of its own, so a second tab would only show the same picture twice
 */
class OpenProjectGraphAction : DumbAwareAction({ t("projectGraph.action.title") }) {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project?.basePath != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val manager = FileEditorManager.getInstance(project)
    val file = manager.openFiles.firstOrNull { it is CodeGraphVirtualFile } ?: CodeGraphVirtualFile()
    manager.openFile(file, true)
  }
}
