// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.defaults

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.vibe.agent.i18n.VibeI18n.t

/**
 * The check-updates action — the same comparison the seeder makes on project open, but on demand
 * and without touching a file.
 *
 * Why it exists: with automatic updates on, the person only ever sees what changed, never whether
 * anything was left behind; with them off, the notification says «there are updates» and the honest
 * answer to «and now?» is this action. The up-to-date answer is worth having, and it must be said
 * out loud rather than shown as an empty window
 */
class VibeCheckSeedUpdatesAction : AnAction(t("seed.check.title")) {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val base = project.basePath ?: return
    ApplicationManager.getApplication().executeOnPooledThread {
      val check = VibeDefaults.check(base)
      ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed) return@invokeLater
        Messages.showInfoMessage(project, report(check), t("seed.check.dialogTitle"))
      }
    }
  }

  private fun report(check: VibeDefaults.SeedCheck): String = buildString {
    if (check.upToDate) {
      append(t("seed.check.upToDate"))
      return@buildString
    }
    if (check.updates.isNotEmpty()) {
      append(t("seed.check.updates", "count" to check.updates.size)).append('\n')
      check.updates.forEach { append("  ").append(it.describe()).append('\n') }
    }
    if (check.conflicts.isNotEmpty()) {
      append(t("seed.check.conflicts", "count" to check.conflicts.size)).append('\n')
      check.conflicts.forEach { c ->
        append("  ").append(c.path).append(": ")
          .append(c.from ?: "—").append(" → ").append(c.to ?: "—").append('\n')
      }
    }
    if (check.missing.isNotEmpty()) {
      append(t("seed.check.missing", "count" to check.missing.size)).append('\n')
      check.missing.forEach { append("  ").append(it).append('\n') }
    }
    if (check.setDrift.isNotEmpty()) {
      append(t("seed.check.drift", "files" to check.setDrift.joinToString(", "))).append('\n')
    }
  }
}
