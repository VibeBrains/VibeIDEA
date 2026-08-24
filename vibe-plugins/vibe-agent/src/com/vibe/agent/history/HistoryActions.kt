// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.history

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
private const val EXPORT_TITLE = "Экспорт истории"
private const val CLEAR_TITLE = "Удаление истории"
private const val CLAIM_TITLE = "Привязка истории"

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
      Messages.showInfoMessage(project, "У текущего проекта нет истории чатов для экспорта.", EXPORT_TITLE)
      return
    }
    val choice = Messages.showDialog(
      project,
      "Формат экспорта истории (${threads.size} тредов)",
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
    val descriptor = FileSaverDescriptor("Сохранить историю проекта", "")
    val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(defaultName) ?: return
    val content = if (choice == FORMAT_MARKDOWN) toMarkdown(threads) else toJsonDump(threads)
    val path = target.file.toPath()
    try {
      Files.writeString(path, content)
    }
    catch (ex: IOException) {
      Messages.showErrorDialog(project, "Не удалось записать файл: ${ex.message}", EXPORT_TITLE)
      return
    }
    Messages.showInfoMessage(project, "История экспортирована: ${threads.size} тредов → $path", EXPORT_TITLE)
  }

  private fun slugOf(name: String): String = name.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")

  private fun toMarkdown(threads: List<ChatThread>): String = buildString {
    for (thread in threads) {
      append("## ").append(thread.title.ifEmpty { thread.id }).append('\n')
      val meta = listOfNotNull(thread.workspaceLabel, thread.createdAt.ifEmpty { null }).joinToString(" · ")
      append('_').append(meta).append("_\n\n")
      for (message in thread.messages) {
        when (message.role) {
          Role.USER -> append("**Вы:** ").append(message.text)
          Role.ASSISTANT -> append("**Агент:** ").append(message.text)
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
    const val OPTION_MARKDOWN = "Markdown (.md) — читаемая стенограмма"
    const val OPTION_JSON = "JSON (.json) — полные данные тредов"
    const val OPTION_CANCEL = "Отмена"
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
      Messages.showInfoMessage(project, "У текущего проекта нет истории чатов.", CLEAR_TITLE)
      return
    }
    val answer = Messages.showYesNoDialog(
      project,
      "Удалить всю историю чатов текущего проекта (${threads.size} тредов)?\n\n" +
        "Затрагиваются только треды, принадлежащие этому проекту. История без проекта и других проектов не трогается. " +
        "Действие необратимо — рекомендуется сначала экспортировать.",
      CLEAR_TITLE,
      "Удалить",
      "Отмена",
      Messages.getWarningIcon(),
    )
    if (answer != Messages.YES) return
    val history = VibeChatHistory.getInstance()
    threads.forEach { history.delete(it.id) }
    Messages.showInfoMessage(project, "Удалено тредов текущего проекта: ${threads.size}.", CLEAR_TITLE)
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
      if (claimed > 0) "Привязано к текущему проекту: $claimed чатов без проекта."
      else "Нет чатов без проекта — привязывать нечего."
    Messages.showInfoMessage(project, message, CLAIM_TITLE)
  }
}
