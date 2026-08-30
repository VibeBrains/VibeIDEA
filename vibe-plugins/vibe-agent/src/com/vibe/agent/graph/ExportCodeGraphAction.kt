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
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tools → "Vibe: export the project graph" → `.vibe/codeGraph.json`.
 * Agents read the file themselves (paths, not content — the VibeIDE principle).
 */
class ExportCodeGraphAction : AnAction({ t("graph.action.title") }) {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, t("graph.task.title"), true) {
      override fun run(indicator: ProgressIndicator) {
        val base = project.basePath ?: return
        val out = Path.of(base, ".vibe", "codeGraph.json")

        // Incremental by design: a repository that changed by three files must not cost a full
        // parse of five thousand. Fingerprints (size + mtime) decide what to re-read.
        val current = CodeGraphBuilder.scan(project)
        val previous = runCatching { if (Files.exists(out)) CodeGraphStore.decode(Files.readString(out)) else emptyList() }
          .getOrDefault(emptyList())
        val (reused, stale) = CodeGraphStore.plan(current, previous)
        indicator.text = if (previous.isEmpty()) t("graph.progress.parsing", "count" to stale.size)
          else t("graph.progress.changed", "changed" to stale.size, "total" to current.size)

        val parsed = CodeGraphBuilder.buildSome(project, stale)
        val nodes = (reused + parsed).sortedBy { it.path }
        val stored = nodes.mapNotNull { node -> current[node.path]?.let { CodeGraphStore.StoredNode(node, it) } }
        val graph = CodeGraphIndex.build(nodes)

        Files.createDirectories(out.parent)
        Files.writeString(out, CodeGraphStore.encode(stored, graph))
        val facts = graph.edges.count { it.provenance == CodeGraphIndex.Provenance.FACT }
        NotificationGroupManager.getInstance().getNotificationGroup("Vibe Agent")
          .createNotification(
            t("graph.done", "files" to nodes.size, "parsed" to parsed.size,
              "edges" to graph.edges.size, "facts" to facts, "guesses" to (graph.edges.size - facts)),
            NotificationType.INFORMATION)
          .notify(project)
      }
    })
  }
}
