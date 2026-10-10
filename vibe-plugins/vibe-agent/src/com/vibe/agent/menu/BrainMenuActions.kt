// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.menu

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager
import com.vibe.agent.ui.AgentPanel
import com.vibe.agent.ui.VibeToolWindows

/**
 * Entries of the brain menu in the window header that had no action of their own
 *
 * The menu itself is a group in `plugin.xml`: everything of ours in one place, a click away and out of sight.
 * Before it our actions were reachable only through Find Action, which finds what a person already knows the name of.
 */

/** New chat — brings the agent panel forward and opens a fresh thread in it */
class VibeNewChatAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(VibeToolWindows.AGENT) ?: return
    toolWindow.activate {
      (toolWindow.contentManager.contents.firstOrNull()?.component as? AgentPanel)?.newChat()
    }
  }
}

/** VibeIDEA settings — our settings root, whatever page was open last */
class VibeOpenSettingsAction : DumbAwareAction() {
  override fun actionPerformed(e: AnActionEvent) {
    ShowSettingsUtil.getInstance().showSettingsDialog(e.project, com.vibe.agent.settings.VibeSettingsRoot::class.java)
  }
}
