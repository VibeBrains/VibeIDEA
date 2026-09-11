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
 * «Выйти из учётной записи агента» — the protocol's `logout`, when the agent declared it.
 *
 * An action next to «Переподключить агента» rather than a `/logout` typed into the chat: what is typed
 * there goes to the agent, and a command intercepted on the way would shadow the agent's own command
 * of the same name.
 */
class VibeLogoutAgentAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(com.vibe.agent.ui.VibeToolWindows.AGENT) ?: return
    toolWindow.activate {
      val panel = toolWindow.contentManager.contents.firstOrNull()?.component as? AgentPanel
      when (panel?.logoutAgent() ?: AgentPanel.Logout.NO_AGENT) {
        AgentPanel.Logout.NO_AGENT -> Messages.showInfoMessage(project, t("logout.nothing"), t("logout.title"))
        AgentPanel.Logout.UNSUPPORTED -> Messages.showInfoMessage(project, t("logout.unsupported"), t("logout.title"))
        AgentPanel.Logout.CANCELLED, AgentPanel.Logout.STARTED -> {}
      }
    }
  }
}
