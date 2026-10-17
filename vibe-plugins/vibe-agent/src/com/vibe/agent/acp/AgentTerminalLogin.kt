// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.acp

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project

/**
 * Runs an agent's terminal sign-in in a tab of the IDE's Terminal tool window, so the person completes it
 * interactively (`claude /login` and the like) without leaving the IDE.
 *
 * The Terminal plugin is an optional dependency: [isAvailable] says whether its classes can be touched, and only
 * [TerminalTab] names them, so nothing here loads without it.
 */
object AgentTerminalLogin {
  private const val TERMINAL_PLUGIN_ID = "org.jetbrains.plugins.terminal"

  fun isAvailable(): Boolean = PluginManagerCore.getPlugin(PluginId.getId(TERMINAL_PLUGIN_ID))?.isEnabled == true

  /** Opens a tab and types the command into it; false when there is no Terminal plugin or the tab failed to open. */
  fun open(project: Project, title: String, commandLine: String): Boolean {
    if (!isAvailable()) return false
    return runCatching { TerminalTab.open(project, title, commandLine) }.isSuccess
  }

  private object TerminalTab {
    fun open(project: Project, title: String, commandLine: String) {
      val widget = org.jetbrains.plugins.terminal.TerminalToolWindowManager.getInstance(project)
        .createNewSession(project.basePath, title, null, true, false)
      widget.sendCommandToExecute(commandLine)
    }
  }
}
