// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Идентификатор панели — ASCII, подпись — из каталога строк.
 *
 * Идентификатор уезжает в layout проекта и в чужие конфигурации, и русская буква там означает
 * файл, который ломается при смене кодировки. Видимое имя при этом обязано переводиться.
 */
class VibeDbToolWindowFactory : ToolWindowFactory {
  override fun init(toolWindow: ToolWindow) {
    toolWindow.stripeTitle = com.vibe.agent.i18n.VibeI18n.t("db.toolWindow")
  }

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val content = ContentFactory.getInstance().createContent(DbPanel(project), "", false)
    toolWindow.contentManager.addContent(content)
  }
}
