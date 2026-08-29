// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.history

import com.vibe.agent.i18n.VibeI18n.t
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.vibe.agent.ui.AgentPanel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDate

/**
 * Command-palette actions over the chat history store (Find Action / Search Everywhere).
 * All actions are project-scoped: they operate on threads stamped with the project's
 * basePath, never on untagged threads or threads of other projects.
 */
private const val TOOL_WINDOW_ID = "VibeAgent"
private const val EXPORT_FILE_PREFIX = "vibeidea-history"
private const val EXT_MARKDOWN = "md"
private const val EXT_JSON = "json"
// Not `const`: a title comes from the catalogue, and the catalogue is chosen at runtime — a
// compile-time constant would freeze the language of these dialogs to whatever was bundled.
private val EXPORT_TITLE get() = t("history.export.title")
private val CLEAR_TITLE get() = t("history.delete.title")
private val CLAIM_TITLE get() = t("history.claim.title")

/** Threads strictly belonging to this project (untagged excluded), non-empty only. */
private fun projectThreads(project: Project): List<ChatThread> {
  val basePath = project.basePath ?: return emptyList()
  return VibeChatHistory.getInstance().all().filter { it.workspaceId == basePath && it.messages.isNotEmpty() }
}

/** «История чата» — activates the tool window and opens the composer's history dropdown. */
class VibeShowChatHistoryAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
    toolWindow.activate {
      val panel = toolWindow.contentManager.contents.firstOrNull()?.component as? AgentPanel
      panel?.openHistoryPopup()
    }
  }
}

/** «Экспортировать историю текущего проекта» — Markdown transcript or full JSON dump. */
class VibeExportProjectHistoryAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project?.basePath != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val threads = projectThreads(project)
    if (threads.isEmpty()) {
      Messages.showInfoMessage(project, t("history.export.nothing"), EXPORT_TITLE)
      return
    }
    val choice = Messages.showDialog(
      project,
      t("history.export.format", "count" to threads.size),
      EXPORT_TITLE,
      arrayOf(OPTION_MARKDOWN, OPTION_JSON, OPTION_CANCEL),
      FORMAT_MARKDOWN,
      Messages.getQuestionIcon(),
    )
    val extension = when (choice) {
      FORMAT_MARKDOWN -> EXT_MARKDOWN
      FORMAT_JSON -> EXT_JSON
      else -> return
    }
    val defaultName = "$EXPORT_FILE_PREFIX-${slugOf(project.name)}-${LocalDate.now()}.$extension"
    val descriptor = FileSaverDescriptor(t("history.export.save"), "")
    val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(defaultName) ?: return
    val content = if (choice == FORMAT_MARKDOWN) toMarkdown(threads) else toJsonDump(threads)
    val path = target.file.toPath()
    try {
      Files.writeString(path, content)
    }
    catch (ex: IOException) {
      Messages.showErrorDialog(project, t("history.export.writeFailed", "reason" to ex.message), EXPORT_TITLE)
      return
    }
    Messages.showInfoMessage(project, t("history.export.done", "count" to threads.size, "path" to path), EXPORT_TITLE)
  }

  private fun slugOf(name: String): String = name.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")

  private fun toMarkdown(threads: List<ChatThread>): String = buildString {
    for (thread in threads) {
      append("## ").append(thread.title.ifEmpty { thread.id }).append('\n')
      val meta = listOfNotNull(thread.workspaceLabel, thread.createdAt.ifEmpty { null }).joinToString(" · ")
      append('_').append(meta).append("_\n\n")
      for (message in thread.messages) {
        when (message.role) {
          Role.USER -> append(t("history.role.user")).append(message.text)
          Role.ASSISTANT -> append(t("history.role.agent")).append(message.text)
          Role.OTHER -> append('_').append(message.text).append('_')
        }
        append("\n\n")
      }
    }
  }

  private fun toJsonDump(threads: List<ChatThread>): String {
    val root = buildJsonObject {
      put("threads", JsonArray(threads.map { ChatTranscriptCodec.toJson(it) }))
    }
    return PRETTY_JSON.encodeToString(JsonObject.serializer(), root)
  }

  private companion object {
    // Not `const`: these are catalogue strings, and the catalogue is chosen at runtime.
    val OPTION_MARKDOWN get() = t("history.export.markdown")
    val OPTION_JSON get() = t("history.export.json")
    val OPTION_CANCEL get() = t("common.cancel")
    const val FORMAT_MARKDOWN = 0
    const val FORMAT_JSON = 1
    val PRETTY_JSON = Json { prettyPrint = true }
  }
}

/** «Удалить историю текущего проекта» — deletes only threads stamped with this project. */
class VibeClearProjectHistoryAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project?.basePath != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val threads = projectThreads(project)
    if (threads.isEmpty()) {
      Messages.showInfoMessage(project, t("history.delete.nothing"), CLEAR_TITLE)
      return
    }
    val answer = Messages.showYesNoDialog(
      project,
      t("history.delete.confirm", "count" to threads.size),
      CLEAR_TITLE,
      t("common.delete"),
      t("common.cancel"),
      Messages.getWarningIcon(),
    )
    if (answer != Messages.YES) return
    val history = VibeChatHistory.getInstance()
    threads.forEach { history.delete(it.id) }
    Messages.showInfoMessage(project, t("history.delete.done", "count" to threads.size), CLEAR_TITLE)
  }
}

/** «Привязать историю без проекта к текущему» — stamps all untagged threads with this project. */
class VibeClaimUntaggedHistoryAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project?.basePath != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val basePath = project.basePath ?: return
    val claimed = VibeChatHistory.getInstance().claimUntagged(basePath, project.name)
    val message =
      if (claimed > 0) t("history.claim.done", "count" to claimed)
      else t("history.claim.nothing")
    Messages.showInfoMessage(project, message, CLAIM_TITLE)
  }
}
