// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import com.intellij.openapi.project.Project
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
  }
}
