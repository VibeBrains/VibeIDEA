// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.vibe.agent.i18n.VibeI18n.t
import java.awt.datatransfer.StringSelection

/**
 * Tells the user that the language server is missing — at the only moment when it matters,
 * the moment they open a file it was supposed to serve.
 *
 * Without this the failure mode is silence: "go to definition" simply does nothing, and
 * nothing on screen connects that to an uninstalled npm package. The notice is shown once
 * per server per project and can be turned off for good, because a notice repeated on every
 * file is how people learn to dismiss notices without reading them.
 */
class VibeLspMissingServerNotifier(private val project: Project) : FileEditorManagerListener {
  override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
    val spec = LspDoctor.serverFor(file.name, LspDoctor.active(PhpServerChoice.stored())) ?: return
    if (ServerBinaries.find(spec.binary) != null) return
    // Shipped with the IDE — nothing to install and nothing to say.
    if (LspDoctor.bundledPath(spec) != null) return

    val properties = PropertiesComponent.getInstance(project)
    val shownKey = "$KEY_SHOWN_PREFIX${spec.id}"
    if (properties.getBoolean(KEY_MUTED, false) || properties.getBoolean(shownKey, false)) return
    properties.setValue(shownKey, true)

    NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
      .createNotification(
        t("lsp.missing.title", "server" to spec.displayName),
        t("lsp.missing.body", "binary" to spec.binary, "command" to LspDoctor.installCommandFor(spec)),
        NotificationType.WARNING,
      )
      .addAction(NotificationAction.createSimple(t("lsp.missing.install")) {
        install(project, spec)
      })
      .addAction(NotificationAction.createSimple(t("lsp.missing.copyCommand")) {
        CopyPasteManager.getInstance().setContents(StringSelection(LspDoctor.installCommandFor(spec)))
      })
      .addAction(NotificationAction.createSimple(t("lsp.missing.mute")) {
        properties.setValue(KEY_MUTED, true)
      })
      .notify(project)
  }

  /**
   * Runs the install command in a terminal of this project.
   *
   * In a terminal rather than silently in the background: the person clicked once and gets to see
   * what is being run and what it says — an install that reports only «готово» is one nobody can
   * debug when it is not.
   */
  private fun install(project: Project, spec: LspDoctor.ServerSpec) {
    if (!ServerInstall.isOfferable(LspDoctor.installCommandFor(spec))) return
    com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
      val terminals = com.vibe.agent.terminal.AgentTerminalService(project.basePath)
      val result = runCatching {
        val shell = ServerInstall.shellCommand(LspDoctor.installCommandFor(spec))
        val id = terminals.create(shell.first(), shell.drop(1), emptyMap(), project.basePath, null)
        val status = terminals.waitForExit(id)
        val output = terminals.output(id)?.output.orEmpty()
        terminals.release(id)
        status?.exitCode to output
      }.getOrElse { null to (it.message ?: "") }

      val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
      // Verified by looking for the binary, not by trusting the exit code: an installer can finish
      // happily and leave nothing on PATH, and «поставил» that did not is the worst answer of all.
      val found = ServerBinaries.find(spec.binary)
      if (found != null) {
        group.createNotification(t("lsp.install.done", "server" to spec.displayName, "path" to found),
                                 NotificationType.INFORMATION).notify(project)
      }
      else {
        group.createNotification(
          t("lsp.install.failed", "server" to spec.displayName, "code" to (result.first ?: -1)),
          ServerInstall.failureTail(result.second), NotificationType.WARNING,
        ).notify(project)
      }
    }
  }

  private companion object {
    const val GROUP = com.vibe.agent.ui.VibeNotifications.LANGUAGES
    const val KEY_SHOWN_PREFIX = "vibe.lsp.missingShown."
    const val KEY_MUTED = "vibe.lsp.missingMuted"
  }
}
