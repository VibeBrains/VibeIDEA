// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graph

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.vibe.agent.i18n.VibeI18n.t

/**
 * A rebuild of the graph as a background task with a progress bar and a cancel button
 *
 * The export action and the project picture start it the same way: a rebuild that takes minutes in silence reads as a hang
 */
object CodeGraphTask {
  /** Starts a rebuild unless one is already running; [onDone] gets the result on the task's thread */
  fun start(project: Project, onDone: (CodeGraphRefresh.Result?) -> Unit = {}): Boolean {
    if (CodeGraphStatus.getInstance(project).building) return false
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, t("graph.task.title"), true) {
      override fun run(indicator: ProgressIndicator) {
        // One refresh shared with the MCP tools: otherwise the file an agent reads and the file a
        // person exports drift apart, and neither of them knows it
        val result = CodeGraphRefresh.refresh(project) { stale, total, firstRun ->
          indicator.text = if (firstRun) t("graph.progress.parsing", "count" to stale)
          else t("graph.progress.changed", "changed" to stale, "total" to total)
        }
        onDone(result)
      }
    })
    return true
  }
}
