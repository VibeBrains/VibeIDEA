// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.vibe.agent.i18n.VibeI18n.t

/**
 * «Починить ошибки в этом файле» — одним действием из редактора.
 *
 * Связывает то, что до сегодня лежало по отдельности: IDE УЖЕ посчитала ошибки, агент УЖЕ умеет
 * читать и править файлы, права УЖЕ решены режимом. Не хватало одного движения, которое соединяет
 * их без пересказа — человеку иначе приходится самому перечислять агенту то, что подчёркнуто у него
 * на экране, и половина смысла агента в IDE теряется именно здесь.
 *
 * Задача формулируется СПИСКОМ ПРОБЛЕМ, а не словами «почини ошибки»: модель, которой назвали
 * строки и сообщения, чинит их, а модель, которой сказали «почини», сперва идёт искать, что чинить,
 * и тратит на это ход.
 *
 * Действие отказывается работать, когда чинить нечего или файл не открыт, и говорит об этом: тихо
 * запущенный ход, который ничего не нашёл, выглядит как поломка.
 */
class VibeFixProblemsAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.VIRTUAL_FILE) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val path = e.getData(CommonDataKeys.VIRTUAL_FILE)?.path ?: return
    // Разметку читаем в фоне: ход агента всё равно уходит в пул, а EDT не место для ожиданий.
    ApplicationManager.getApplication().executeOnPooledThread {
      val problems = IdeProblems.of(project, path)
      when {
        problems == null -> notify(project, t("fix.notOpen"))
        problems.isEmpty() -> notify(project, t("fix.nothing"))
        else -> {
          val task = buildString {
            appendLine(t("fix.task", "path" to path))
            problems.forEach { appendLine("${it.line}: [${it.severity}] ${it.message}  |  ${it.text}") }
            append(t("fix.rules"))
          }
          runCatching { com.vibe.agent.http.VibeAgentGateway.getInstance().run(task, null, wait = false) }
            .onFailure { notify(project, t("fix.noPanel")) }
        }
      }
    }
  }

  private fun notify(project: Project, text: String) {
    com.intellij.notification.NotificationGroupManager.getInstance()
      .getNotificationGroup(com.vibe.agent.ui.VibeNotifications.AGENT)
      .createNotification(text, com.intellij.notification.NotificationType.INFORMATION)
      .notify(project)
  }
}
