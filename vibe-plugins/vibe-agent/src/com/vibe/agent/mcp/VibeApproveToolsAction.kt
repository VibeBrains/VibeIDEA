// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.providers.ToolSpec

/**
 * "Confirm MCP server tools" — accept a changed tool set deliberately.
 *
 * Without this action the description-swap guard ([ToolFingerprint]) would become a trap: a server that updated its
 * tools honestly would lose them for good, and the only way out would be removing it from the file and adding it back.
 *
 * An action rather than a button in the feed: people confirm after they have LOOKED at the changes, which is a separate
 * step in time, not a reflex to a line of text.
 *
 * The set is re-read from the server here rather than taken from the panel's memory: what gets confirmed must be what
 * the server returns now, otherwise the person agrees to a stale snapshot.
 */
class VibeApproveToolsAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val base = project.basePath
    ApplicationManager.getApplication().executeOnPooledThread {
      val drifted = McpServersFile.load(base).servers
        .filterNot { it.disabled }
        .mapNotNull { entry ->
          val specs = runCatching { read(entry, base) }.getOrNull() ?: return@mapNotNull null
          val drift = ApprovedTools.drift(base, entry.name, specs)
          if (drift.isEmpty) null else Triple(entry.name, specs, drift)
        }
      ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed) return@invokeLater
        if (drifted.isEmpty()) {
          Messages.showInfoMessage(project, t("mcp.approve.none"), t("mcp.approve.title"))
          return@invokeLater
        }
        val labels = drifted.map { (name, _, drift) -> DriftMessage.of(name, drift) }.toTypedArray()
        val choice = Messages.showChooseDialog(
          project, t("mcp.approve.choose"), t("mcp.approve.title"), null, labels, labels.first(),
        )
        if (choice < 0) return@invokeLater
        val (name, specs, _) = drifted[choice]
        ApprovedTools.approve(base, name, specs)
        Messages.showInfoMessage(project, t("mcp.approve.done", "server" to name), t("mcp.approve.title"))
      }
    }
  }

  /** Ask the server for its current set over a separate short-lived connection. */
  private fun read(entry: McpServersFile.Entry, base: String?): List<ToolSpec> {
    val dir = base?.let { java.nio.file.Path.of(it) }
    val version = com.intellij.openapi.application.ApplicationInfo.getInstance().fullVersion
    return McpStdioClient.start(entry.command, entry.args, dir, entry.env).use { client ->
      client.initialize(version, DirectChatTools.CALL_TIMEOUT_MS)
      client.listTools(DirectChatTools.CALL_TIMEOUT_MS).map { ToolSpec(it.name, it.description, it.inputSchema) }
    }
  }
}
