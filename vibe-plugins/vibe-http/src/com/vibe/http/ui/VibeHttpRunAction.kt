// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.http.HttpFileType
import com.vibe.http.HttpRequestFile

/**
 * «Выполнить запрос» — из редактора, по тому запросу, где стоит курсор.
 *
 * Из редактора, а не только из панели: запрос пишут и запускают в одном месте, и уводить руку на
 * список ради каждого прогона — ровно то трение, из-за которого в итоге открывают Postman.
 */
class VibeHttpRunAction : AnAction({ t("http.action.run") }) {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = requestAtCaret(e) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val request = requestAtCaret(e) ?: return
    val window = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW) ?: return
    window.activate {
      val panel = window.contentManager.contents.firstNotNullOfOrNull { it.component as? HttpPanel } ?: return@activate
      // Перечитываем перед запуском: файл могли править секунду назад, и выполнять надо то, что на
      // экране, а не то, что панель показала при последнем открытии.
      panel.reload()
      panel.run(request)
    }
  }

  private fun requestAtCaret(e: AnActionEvent): HttpRequestFile.Request? {
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
    val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
    if (file.extension?.lowercase() !in HttpFileType.EXTENSIONS) return null
    val parsed = HttpRequestFile.parse(editor.document.text)
    return HttpRequestFile.requestAt(parsed, editor.caretModel.logicalPosition.line)
  }

  private companion object {
    const val TOOL_WINDOW = "HTTP"
  }
}
