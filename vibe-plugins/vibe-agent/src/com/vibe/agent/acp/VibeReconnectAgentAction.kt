// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.ui.AgentPanel

/**
 * «Переподключить агента» — заново поднять соединение с внешним ACP-агентом.
 *
 * Отдельным действием, а не кнопкой в панели, намеренно: чинить связь приходится ровно тогда,
 * когда панель уже ведёт себя странно, и лишний элемент в её шапке в этот момент только мешает.
 * Действие ищется по имени в палитре и лежит рядом с диагностикой — двумя вещами, за которыми
 * идут, когда что-то не отвечает.
 */
class VibeReconnectAgentAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(com.vibe.agent.ui.VibeToolWindows.AGENT) ?: return
    toolWindow.activate {
      val panel = toolWindow.contentManager.contents.firstOrNull()?.component as? AgentPanel
      // Нечего переподключать — это не ошибка, а состояние: агент ещё ни разу не запускался.
      if (panel?.reconnectAgent() != true) {
        Messages.showInfoMessage(project, t("reconnect.nothing"), t("reconnect.title"))
      }
    }
  }
}
