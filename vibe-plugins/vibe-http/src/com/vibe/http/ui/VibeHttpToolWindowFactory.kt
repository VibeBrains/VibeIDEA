// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class VibeHttpToolWindowFactory : ToolWindowFactory {
  /** Подпись — из каталога строк: идентификатор панели ASCII и не переводится, имя переводится. */
  override fun init(toolWindow: com.intellij.openapi.wm.ToolWindow) {
    toolWindow.stripeTitle = com.vibe.agent.i18n.VibeI18n.t("toolWindow.http")
  }

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val content = ContentFactory.getInstance().createContent(HttpPanel(project), "", false)
    toolWindow.contentManager.addContent(content)
  }
}
