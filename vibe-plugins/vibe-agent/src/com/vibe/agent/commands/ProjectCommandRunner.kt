// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.commands

import com.intellij.execution.ExecutionException
import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.vibe.agent.i18n.VibeI18n.t
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs a project command, wherever it was started from: the Commands menu, the list of all commands, a number key
 *
 * One place on purpose: the approval, the substitution, the folder check and the process are the security model,
 * and a second copy of that logic is a second place for it to drift.
 *
 * The output goes to the Run tool window: the person sees it, can stop the process and start it again.
 * The agent's own terminal is not used — it belongs to the agent's turn and shows nothing to the person.
 */
@Service(Service.Level.PROJECT)
class ProjectCommandRunner(private val project: Project) {
  /** Running processes by command id, for `singleton` */
  private val running = ConcurrentHashMap<String, ProcessHandler>()

  /** The file as it is now; null when the project has none */
  fun load(): ProjectCommands.Parsed? {
    val file = file() ?: return null
    if (!Files.isRegularFile(file)) return null
    return ProjectCommands.parse(runCatching { Files.readString(file) }.getOrDefault(""))
  }

  fun file(): Path? = project.basePath?.let { Path.of(it, ProjectCommands.FILE) }

  fun run(command: ProjectCommands.Command) {
    val base = project.basePath ?: return
    // A workflow is VibeIDE's; running the command written next to it would do something the author did not ask for
    if (command.workflowId != null) {
      warn(t("commands.workflow", "name" to command.name, "workflow" to command.workflowId))
      return
    }
    if (command.singleton && running[command.id]?.isProcessTerminated == false) {
      warn(t("commands.alreadyRunning", "name" to command.name))
      return
    }
    if (!approved(command)) return
    val resolved = ProjectCommands.resolve(
      command,
      // .vibe/.env first, then the process environment: the project's own file is the answer
      // people expect, and the environment is the fallback for CI.
      secret = { name -> com.vibe.agent.providers.ApiKeyResolver.dotEnv(base)[name] ?: System.getenv(name) },
      environment = { name -> System.getenv(name) },
    )
    if (resolved.missing.isNotEmpty()) {
      warn(t("commands.missingSecrets", "names" to resolved.missing.joinToString(", ")))
      return
    }
    val cwd = workingDirectory(base, resolved.cwd) ?: run {
      warn(t("commands.cwdOutside", "name" to command.name, "cwd" to resolved.cwd.orEmpty()))
      return
    }
    audit(command)
    val handler = try {
      ColoredProcessHandler(commandLine(resolved, cwd, command.shell))
    }
    catch (e: ExecutionException) {
      warn(t("commands.failed", "name" to command.name, "reason" to (e.message ?: e.javaClass.simpleName)))
      return
    }
    running[command.id] = handler
    // VibeIDE's `external` (a window of the system terminal) has no counterpart here and runs as `integrated`
    RunContentExecutor(project, handler)
      .withTitle(command.name)
      .withActivateToolWindow(command.terminal != ProjectCommands.Terminal.BACKGROUND)
      .withStop({ handler.destroyProcess() }, { !handler.isProcessTerminated })
      .withRerun { run(command) }
      .withAfterCompletion { running.remove(command.id, handler) }
      .run()
  }

  /**
   * The folder to run in: the project root, or `cwd` under it
   * Null when the real path leaves the project — a symlink included, which is why the check is on the real path
   */
  private fun workingDirectory(base: String, cwd: String?): Path? {
    val root = runCatching { Path.of(base).toRealPath() }.getOrNull() ?: return null
    if (cwd == null) return root
    val target = runCatching { root.resolve(cwd).toRealPath() }.getOrNull() ?: return null
    return target.takeIf { it.startsWith(root) && Files.isDirectory(it) }
  }

  private fun commandLine(resolved: ProjectCommands.Resolved, cwd: Path, shell: Boolean): GeneralCommandLine {
    val line = if (shell) {
      // The author asked for a shell: the line goes to it as written, metacharacters and all
      val text = (listOf(resolved.command) + resolved.args).joinToString(" ")
      if (SystemInfo.isWindows) GeneralCommandLine("cmd.exe", "/c", text) else GeneralCommandLine("/bin/sh", "-c", text)
    }
    else {
      GeneralCommandLine(resolved.command).withParameters(resolved.args)
    }
    return line.withWorkingDirectory(cwd)
      .withEnvironment(resolved.env)
      .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
      .withCharset(Charsets.UTF_8)
  }

  /**
   * The approval is remembered per project, per exact shape of the command, and OUTSIDE the repository:
   * a file inside it would arrive with the pull request that brought the command and approve it by itself
   */
  private fun approved(command: ProjectCommands.Command): Boolean {
    val properties = PropertiesComponent.getInstance(project)
    val key = com.vibe.agent.safety.ApprovalKeys.COMMAND + command.id
    val hash = ProjectCommands.approvalHash(command)
    if (!command.confirm && properties.getValue(key) == hash) return true
    val answer = Messages.showYesNoDialog(
      project,
      if (command.confirm) t("commands.confirm", "title" to command.name, "command" to command.line)
      else t("commands.approve", "title" to command.name, "command" to command.line),
      t("commands.title"), t("commands.approve.yes"), t("common.cancel"), Messages.getWarningIcon(),
    )
    if (answer != Messages.YES) return false
    properties.setValue(key, hash)
    return true
  }

  /**
   * Names only — never values. The audit answers what left together with the command, and a log that
   * answered it by printing the token would be the leak it exists to investigate.
   */
  private fun audit(command: ProjectCommands.Command) {
    if (command.secretNames.isEmpty()) return
    com.vibe.agent.audit.VibeAuditService.getInstance(project).get()?.append(
      com.vibe.agent.audit.AuditEvent(
        ts = System.currentTimeMillis(),
        action = com.vibe.agent.audit.AuditEvent.Action.SECRET_USED,
        ok = true,
        actor = com.vibe.agent.audit.AuditActor.HUMAN,
        meta = mapOf("names" to command.secretNames.joinToString(","), "consumer" to command.name),
      )
    )
  }

  private fun warn(message: String) = Messages.showWarningDialog(project, message, t("commands.title"))

  companion object {
    fun getInstance(project: Project): ProjectCommandRunner = project.service()
  }
}
