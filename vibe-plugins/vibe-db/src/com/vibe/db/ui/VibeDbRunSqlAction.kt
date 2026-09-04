// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.db.SqlStatements

/**
 * «Выполнить SQL-оператор» — из файла `.sql`, по тому оператору, где стоит курсор.
 *
 * Запросы и миграции живут в файлах проекта, а не в консоли панели: их правят, обсуждают в
 * код-ревью и откатывают вместе с кодом. Требовать переносить оператор в консоль ради запуска —
 * то же трение, из-за которого в итоге открывают отдельный клиент.
 *
 * Тип файла не регистрируем: `.sql` уже обслуживается TextMate-грамматикой платформы, и своим
 * языком мы отобрали бы у неё подсветку, ничего не дав взамен. Действие смотрит на расширение.
 */
class VibeDbRunSqlAction : AnAction({ t("db.action.runSql") }) {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = statementAtCaret(e) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val statement = statementAtCaret(e) ?: return
    val window = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW) ?: return
    window.activate {
      val panel = window.contentManager.contents.firstNotNullOfOrNull { it.component as? DbPanel } ?: return@activate
      panel.runFromEditor(statement.text)
    }
  }

  private fun statementAtCaret(e: AnActionEvent): SqlStatements.Statement? {
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
    val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
    if (file.extension?.lowercase() != "sql") return null
    val statements = SqlStatements.split(editor.document.text)
    return SqlStatements.statementAt(statements, editor.caretModel.logicalPosition.line)
  }

  private companion object {
    const val TOOL_WINDOW = com.vibe.agent.ui.VibeToolWindows.DB
  }
}
