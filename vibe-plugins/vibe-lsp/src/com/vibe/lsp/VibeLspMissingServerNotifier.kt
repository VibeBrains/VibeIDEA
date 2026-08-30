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
    val spec = LspDoctor.serverFor(file.name) ?: return
    if (ServerBinaries.find(spec.binary) != null) return

    val properties = PropertiesComponent.getInstance(project)
    val shownKey = "$KEY_SHOWN_PREFIX${spec.id}"
    if (properties.getBoolean(KEY_MUTED, false) || properties.getBoolean(shownKey, false)) return
    properties.setValue(shownKey, true)

    NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
      .createNotification(
        t("lsp.missing.title", "server" to spec.displayName),
        t("lsp.missing.body", "binary" to spec.binary, "command" to spec.installCommand),
        NotificationType.WARNING,
      )
      .addAction(NotificationAction.createSimple(t("lsp.missing.copyCommand")) {
        CopyPasteManager.getInstance().setContents(StringSelection(spec.installCommand))
      })
      .addAction(NotificationAction.createSimple(t("lsp.missing.mute")) {
        properties.setValue(KEY_MUTED, true)
      })
      .notify(project)
  }

  private companion object {
    const val GROUP = "Vibe Languages"
    const val KEY_SHOWN_PREFIX = "vibe.lsp.missingShown."
    const val KEY_MUTED = "vibe.lsp.missingMuted"
  }
}
