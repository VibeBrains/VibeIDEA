// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.commands

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.vibe.agent.i18n.VibeI18n.t
import java.nio.file.Files
import java.nio.file.Path

/**
 * The project's own commands, offered as a list and run through the agent terminal.
 *
 * Approval is per exact text and remembered per project: the first run asks, an edited command asks
 * again. That is the whole security model, and it is deliberately boring — a dialog that appears
 * every time is a dialog people stop reading, and one that never appears is not a gate.
 */
class VibeProjectCommandsAction : AnAction({ t("commands.action") }) {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val base = project.basePath ?: return
    val file = Path.of(base, ProjectCommands.FILE)
    if (!Files.isRegularFile(file)) {
      Messages.showInfoMessage(project, t("commands.none", "path" to ProjectCommands.FILE), t("commands.title"))
      return
    }
    val parsed = ProjectCommands.parse(runCatching { Files.readString(file) }.getOrDefault(""))
    if (parsed.problems.isNotEmpty()) {
      Messages.showWarningDialog(project, t("commands.problems", "problems" to parsed.problems.joinToString(", ")),
                                 t("commands.title"))
    }
    if (parsed.commands.isEmpty()) return
    JBPopupFactory.getInstance()
      .createPopupChooserBuilder(parsed.commands.map { it.title + "  ·  " + ProjectCommands.forLog(it.command) })
      .setTitle(t("commands.title"))
      .setItemChosenCallback { chosen ->
        val index = parsed.commands.indexOfFirst { chosen.startsWith(it.title + "  ·  ") }
        parsed.commands.getOrNull(index)?.let { run(project, it) }
      }
      .createPopup()
      .showCenteredInCurrentWindow(project)
  }

  private fun run(project: Project, command: ProjectCommands.Command) = runCommand(project, command)

  private fun runInternal(project: Project, command: ProjectCommands.Command) {
    if (!approvedFor(project, command)) return
    val resolved = ProjectCommands.substituteSecrets(command.command) { name ->
      // .vibe/.env first, then the process environment: the project's own file is the answer
      // people expect, and the environment is the fallback for CI.
      com.vibe.agent.providers.ApiKeyResolver.dotEnv(project.basePath)[name] ?: System.getenv(name)
    }
    val missing = command.secretNames.filter { resolved.contains("\${secret:" + it + "}") }
    if (missing.isNotEmpty()) {
      Messages.showWarningDialog(project, t("commands.missingSecrets", "names" to missing.joinToString(", ")),
                                 t("commands.title"))
      return
    }
    // Its own short-lived terminal service: the panel's one belongs to the agent's turn, and a
    // command started by a person must not disappear when that turn ends.
    com.vibe.agent.terminal.AgentTerminalService(project.basePath)
      .create(resolved, emptyList(), emptyMap(), project.basePath, null)
    Messages.showInfoMessage(project, t("commands.started", "title" to command.title), t("commands.title"))
  }

  /** The approval is remembered per project and per exact command text. */
  private fun approvedFor(project: Project, command: ProjectCommands.Command): Boolean {
    val properties = PropertiesComponent.getInstance(project)
    val key = KEY_PREFIX + command.id
    val hash = ProjectCommands.approvalHash(command)
    if (properties.getValue(key) == hash) return true
    val answer = Messages.showYesNoDialog(
      project,
      t("commands.approve", "title" to command.title, "command" to ProjectCommands.forLog(command.command)),
      t("commands.title"), t("commands.approve.yes"), t("common.cancel"), Messages.getWarningIcon(),
    )
    if (answer != Messages.YES) return false
    properties.setValue(key, hash)
    return true
  }

  companion object {
    private const val KEY_PREFIX = "vibe.commands.approved."

    /**
     * Shared with the number-key actions: the approval, the secret substitution and the terminal
     * are the same wherever the command was started from — a second copy of that logic is a second
     * place for the security model to drift.
     */
    fun runCommand(project: Project, command: ProjectCommands.Command) {
      VibeProjectCommandsAction().runInternal(project, command)
    }
  }
}
