// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.graph

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.ProgressManager
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tools → "Vibe: экспорт графа проекта" → `.vibe/codeGraph.json`.
 * Agents read the file themselves (paths, not content — the VibeIDE principle).
 */
class ExportCodeGraphAction : AnAction("Vibe: экспорт графа проекта") {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Vibe: граф проекта", true) {
      override fun run(indicator: ProgressIndicator) {
        val nodes = CodeGraphBuilder.build(project)
        val json = buildJsonObject {
          put("version", 1)
          put("generatedBy", "VibeIDEA code_graph (первый срез: файлы, top-level символы, импорты UAST, TODO)")
          put("nodes", JsonArray(nodes.map { n ->
            buildJsonObject {
              put("path", n.path)
              if (n.symbols.isNotEmpty()) put("symbols", JsonArray(n.symbols.map { JsonPrimitive(it) }))
              if (n.imports.isNotEmpty()) put("imports", JsonArray(n.imports.map { JsonPrimitive(it) }))
              if (n.todos.isNotEmpty()) put("todos", JsonArray(n.todos.map { JsonPrimitive(it) }))
            }
          }))
        }
        val base = project.basePath ?: return
        val out = Path.of(base, ".vibe", "codeGraph.json")
        Files.createDirectories(out.parent)
        Files.writeString(out, json.toString())
        NotificationGroupManager.getInstance().getNotificationGroup("Vibe Agent")
          .createNotification("Граф проекта выгружен: .vibe/codeGraph.json (${nodes.size} файлов)", NotificationType.INFORMATION)
          .notify(project)
      }
    })
  }
}
