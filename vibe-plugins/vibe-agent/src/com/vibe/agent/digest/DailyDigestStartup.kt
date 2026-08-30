// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.digest

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.Messages
import com.vibe.agent.budget.SpendLedger
import com.vibe.agent.budget.VibeSpendService
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.runs.VibeAgentRunService
import com.vibe.agent.settings.VibeAgentSettings
import java.time.LocalDate
import java.time.LocalTime
import javax.swing.Timer

/**
 * Sends the daily digest at the configured time, once per day.
 *
 * Checked on a timer rather than scheduled precisely: an IDE is not a cron, it is closed at night
 * and opened at random hours, and a mechanism that fires only when the machine happened to be awake
 * at 09:00 sharp would never fire at all.
 */
class DailyDigestStartup : ProjectActivity {
  override suspend fun execute(project: Project) {
    if (DailyDigest.minutesOfDay(VibeAgentSettings.digestTime) == null) return
    Timer(CHECK_INTERVAL_MS) { maybeSend(project) }.apply { isRepeats = true; start() }
    maybeSend(project)
  }

  private fun maybeSend(project: Project) {
    if (project.isDisposed) return
    val scheduled = DailyDigest.minutesOfDay(VibeAgentSettings.digestTime) ?: return
    val properties = PropertiesComponent.getInstance(project)
    val today = LocalDate.now().toEpochDay()
    val lastSent = properties.getValue(KEY_LAST_SENT)?.toLongOrNull() ?: 0L
    val now = LocalTime.now().let { it.hour * 60 + it.minute }
    if (!DailyDigest.shouldSend(now, today, scheduled, lastSent)) return
    properties.setValue(KEY_LAST_SENT, today.toString())
    val text = DailyDigestAction.text(project)
    NotificationGroupManager.getInstance().getNotificationGroup("Vibe Agent")
      .createNotification(t("digest.title"), text, NotificationType.INFORMATION)
      .notify(project)
  }

  private companion object {
    /** Every ten minutes: the grace window is two hours, so this is far more often than needed. */
    const val CHECK_INTERVAL_MS = 10 * 60 * 1000
    const val KEY_LAST_SENT = "vibe.digest.lastSentDay"
  }
}

/** The same digest on demand — the answer to «что тут было, пока меня не было». */
class DailyDigestAction : AnAction({ t("digest.action") }) {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    Messages.showInfoMessage(project, text(project), t("digest.title"))
  }

  companion object {
    fun text(project: Project): String {
      val since = System.currentTimeMillis() - SpendLedger.DAY_MS
      val stats = DailyDigest.collect(
        VibeAgentRunService.getInstance(project).runs(),
        VibeSpendService.getInstance().entries(SpendLedger.DAY_MS),
        since,
      )
      return DailyDigest.render(stats, labels())
    }

    private fun labels() = object : DailyDigest.Labels {
      override val quiet: String get() = t("digest.quiet")
      override fun runs(count: Int, changedFiles: Int) = t("digest.runs", "count" to count, "files" to changedFiles)
      override fun problems(failed: Int, orphaned: Int) = t("digest.problems", "failed" to failed, "orphaned" to orphaned)
      override fun spend(tokens: Long, topRole: String?) =
        t("digest.spend", "tokens" to "%,d".format(tokens), "role" to (topRole ?: "-"))
    }
  }
}
