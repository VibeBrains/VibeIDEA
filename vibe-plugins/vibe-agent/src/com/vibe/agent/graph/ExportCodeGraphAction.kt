// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graph

import com.vibe.agent.i18n.VibeI18n.t

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/**
 * Brain menu → Project → "Vibe: export the project graph" → `.vibe/codeGraph.json`.
 * Agents read the file themselves (paths, not content — the VibeIDE principle).
 */
class ExportCodeGraphAction : AnAction({ t("graph.action.title") }) {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val started = CodeGraphTask.start(project) { result ->
      if (result == null) return@start
      val graph = result.graph
      val facts = graph.edges.count { it.provenance == CodeGraphIndex.Provenance.FACT }
      notify(project, t("graph.done", "files" to result.files, "parsed" to result.parsed,
                        "edges" to graph.edges.size, "facts" to facts, "guesses" to (graph.edges.size - facts)))
    }
    // A press that does nothing reads as a broken button: say that the build it asks for is already going
    if (!started) notify(project, t("graph.alreadyBuilding"))
  }

  private fun notify(project: Project, text: String) {
    NotificationGroupManager.getInstance().getNotificationGroup(com.vibe.agent.ui.VibeNotifications.AGENT)
      .createNotification(text, NotificationType.INFORMATION)
      .notify(project)
  }
}
