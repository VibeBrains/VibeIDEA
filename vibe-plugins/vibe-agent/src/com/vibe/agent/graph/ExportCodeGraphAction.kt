// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.graph

import com.vibe.agent.i18n.VibeI18n.t

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.ProgressManager

/**
 * Tools → "Vibe: export the project graph" → `.vibe/codeGraph.json`.
 * Agents read the file themselves (paths, not content — the VibeIDE principle).
 */
class ExportCodeGraphAction : AnAction({ t("graph.action.title") }) {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, t("graph.task.title"), true) {
      override fun run(indicator: ProgressIndicator) {
        // One refresh shared with the MCP tools: otherwise the file an agent reads and the file a
        // person exports drift apart, and neither of them knows it.
        val result = CodeGraphRefresh.refresh(project) { stale, total, firstRun ->
          indicator.text = if (firstRun) t("graph.progress.parsing", "count" to stale)
          else t("graph.progress.changed", "changed" to stale, "total" to total)
        } ?: return
        val graph = result.graph
        val facts = graph.edges.count { it.provenance == CodeGraphIndex.Provenance.FACT }
        NotificationGroupManager.getInstance().getNotificationGroup(com.vibe.agent.ui.VibeNotifications.AGENT)
          .createNotification(
            t("graph.done", "files" to result.files, "parsed" to result.parsed,
              "edges" to graph.edges.size, "facts" to facts, "guesses" to (graph.edges.size - facts)),
            NotificationType.INFORMATION)
          .notify(project)
      }
    })
  }
}
