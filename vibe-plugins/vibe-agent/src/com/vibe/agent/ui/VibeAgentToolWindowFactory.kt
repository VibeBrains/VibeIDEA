// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.settings.VibeProvidersConfigurable
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class VibeAgentToolWindowFactory : ToolWindowFactory {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = AgentPanel(project)
    val content = ContentFactory.getInstance().createContent(panel, "", false)
    // The panel owns the agent process and popups; it dies with the content.
    content.setDisposer(panel)
    content.preferredFocusableComponent = panel.preferredFocusComponent
    toolWindow.contentManager.addContent(content)
    installSettingsAction(project, toolWindow)
  }

  /**
   * Настройки — в шапке панели, а не пилюлей в композере.
   *
   * Ряд композера — это то, что человек трогает каждым сообщением: модель, режим, права. Настройки
   * открывают раз в месяц, и место в этом ряду они занимали постоянно (решение владельца
   * 18.09.2026). Шестерёнка панели — то самое место, где их ищут по привычке из любой другой
   * панели IDE.
   */
  private fun installSettingsAction(project: Project, toolWindow: ToolWindow) {
    val ex = toolWindow as? ToolWindowEx ?: return
    ex.setTitleActions(listOf(object : DumbAwareAction(t("chat.settingsPill"), t("chat.settingsPill"), AllIcons.General.Settings) {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
      override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, VibeProvidersConfigurable::class.java)
      }
    }))
  }
}
