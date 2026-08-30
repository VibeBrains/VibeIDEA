// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.gates

import com.vibe.agent.i18n.VibeI18n.t

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages

/**
 * «Предохранители агента» — view and clear the latching security breakers. Mirrors
 * VibeIDE's command: a tripped protective breaker blocks the agent from starting a
 * turn until the user clears it here (or confirms the clear on the next attempt).
 */
class VibeBreakersAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
  override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val breakers = VibeBreakerService.getInstance(project)
    if (!breakers.isBlocking()) {
      Messages.showInfoMessage(project, t("breakers.none"), t("breakers.dialogTitle"))
      return
    }
    val confirm = Messages.showYesNoDialog(project,
      t("breakers.confirm", "reasons" to breakers.openReasons().joinToString("\n")),
      t("breakers.dialogTitle"), t("breakers.reset"), t("common.cancel"), Messages.getWarningIcon())
    if (confirm == Messages.YES) {
      val n = breakers.clearAll()
      // Clear the ⛔ status-bar state left by the last blocked turn.
      com.vibe.agent.ui.VibeAgentStatusService.getInstance(project).set(com.vibe.agent.ui.VibeAgentStatusService.State.IDLE)
      Messages.showInfoMessage(project, t("breakers.resetDone", "count" to n), t("breakers.dialogTitle"))
    }
  }
}
