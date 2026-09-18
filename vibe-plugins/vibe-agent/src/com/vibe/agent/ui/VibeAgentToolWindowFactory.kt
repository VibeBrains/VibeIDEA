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
    installTitleActions(project, toolWindow, panel)
  }

  /**
   * Настройки — в шапке панели, а не пилюлей в композере.
   *
   * Ряд композера — это то, что человек трогает каждым сообщением: модель, режим, права. Настройки
   * открывают раз в месяц, и место в этом ряду они занимали постоянно (решение владельца
   * 18.09.2026). Шестерёнка панели — то самое место, где их ищут по привычке из любой другой
   * панели IDE.
   */
  private fun installTitleActions(project: Project, toolWindow: ToolWindow, panel: AgentPanel) {
    val ex = toolWindow as? ToolWindowEx ?: return
    ex.setTitleActions(listOf(
      action(t("export.title"), AllIcons.ToolbarDecorator.Export) { panel.exportConversation() },
      action(t("import.title"), AllIcons.ToolbarDecorator.Import) { panel.importConversation() },
      action(t("chat.settingsPill"), AllIcons.General.Settings) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, VibeProvidersConfigurable::class.java)
      },
    ))
  }

  private fun action(title: String, icon: javax.swing.Icon, perform: () -> Unit) =
    object : DumbAwareAction(title, title, icon) {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
      override fun actionPerformed(e: AnActionEvent) = perform()
    }
}
