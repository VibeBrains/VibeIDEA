// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.http

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
class VibeShowApiTokenAction : AnAction("VibeIDEA: показать токен HTTP API") {
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
            "HTTP API выключен. Включить: Settings → Tools → VibeIDEA → Агент → «Входящий HTTP API».",
            "VibeIDEA: HTTP API")
          return@invokeLater
        }
        if (token == null) {
          Messages.showErrorDialog(project, "Токен недоступен: хранилище паролей не ответило.", "VibeIDEA: HTTP API")
          return@invokeLater
        }
        val address = if (port > 0) "http://127.0.0.1:$port" else "порт ещё не занят"
        val answer = Messages.showYesNoDialog(project,
          "Адрес: $address\nТокен: $token\n\nТокен даёт право запускать агента на этой машине — не пересылайте его.",
          "VibeIDEA: HTTP API", "Скопировать токен", "Закрыть", null)
        if (answer == Messages.YES) CopyPasteManager.getInstance().setContents(StringSelection(token))
      }
    }
  }
}

/** Issues a new token and forgets the old one — what to press when the token leaked. */
class VibeRegenerateApiTokenAction : AnAction("VibeIDEA: перевыпустить токен HTTP API") {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project
    val confirmed = Messages.showYesNoDialog(project,
      "Старый токен перестанет работать немедленно. Скрипты и CI, которые им пользуются, придётся обновить.",
      "Перевыпустить токен?", "Перевыпустить", "Отмена", null) == Messages.YES
    if (!confirmed) return
    ApplicationManager.getApplication().executeOnPooledThread {
      val token = runCatching { VibeApiToken.regenerate() }.getOrNull()
      ApplicationManager.getApplication().invokeLater {
        if (token == null) Messages.showErrorDialog(project, "Не удалось записать токен в хранилище.", "VibeIDEA: HTTP API")
        else Messages.showInfoMessage(project, "Новый токен: $token", "VibeIDEA: HTTP API")
      }
    }
  }
}
