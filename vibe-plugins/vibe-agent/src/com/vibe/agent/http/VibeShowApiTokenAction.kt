// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.http

import com.vibe.agent.i18n.VibeI18n.t
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.Messages
import com.vibe.agent.settings.VibeAgentSettings
import java.awt.datatransfer.StringSelection

/**
 * Shows the HTTP API token inside the IDE — the only place it is ever revealed. The API itself
 * never returns it, and it is not kept in synchronised settings.
 */
class VibeShowApiTokenAction : AnAction(t("httpToken.showAction")) {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project
    // PasswordSafe may block on the OS keychain — off the EDT, then back for the dialog.
    ApplicationManager.getApplication().executeOnPooledThread {
      val enabled = VibeAgentSettings.httpApiEnabled
      val token = runCatching { if (enabled) VibeApiToken.getOrCreate() else VibeApiToken.peek() }.getOrNull()
      val service = VibeHttpApiService.getInstance()
      val port = service.port
      ApplicationManager.getApplication().invokeLater {
        if (!enabled) {
          Messages.showInfoMessage(project,
            t("httpToken.disabled"),
            t("httpToken.title"))
          return@invokeLater
        }
        if (token == null) {
          Messages.showErrorDialog(project, t("httpToken.unavailable"), t("httpToken.title"))
          return@invokeLater
        }
        val address = if (port > 0) "http://127.0.0.1:$port" else t("httpToken.noPort")
        val answer = Messages.showYesNoDialog(project,
          t("httpToken.body", "address" to address, "token" to token),
          t("httpToken.title"), t("httpToken.copy"), t("common.close"), null)
        if (answer == Messages.YES) CopyPasteManager.getInstance().setContents(StringSelection(token))
      }
    }
  }
}

/** Issues a new token and forgets the old one — what to press when the token leaked. */
class VibeRegenerateApiTokenAction : AnAction(t("httpToken.regenerateAction")) {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project
    val confirmed = Messages.showYesNoDialog(project,
      t("httpToken.regenerateConfirm"),
      t("httpToken.regenerateTitle"), t("httpToken.regenerate"), t("common.cancel"), null) == Messages.YES
    if (!confirmed) return
    ApplicationManager.getApplication().executeOnPooledThread {
      val token = runCatching { VibeApiToken.regenerate() }.getOrNull()
      ApplicationManager.getApplication().invokeLater {
        if (token == null) Messages.showErrorDialog(project, t("httpToken.writeFailed"), t("httpToken.title"))
        else Messages.showInfoMessage(project, t("httpToken.newToken", "token" to token), t("httpToken.title"))
      }
    }
  }
}
