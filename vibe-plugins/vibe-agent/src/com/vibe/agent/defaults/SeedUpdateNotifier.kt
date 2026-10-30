// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.defaults

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.providers.ProvidersChangeListener
import java.nio.file.Path

/**
 * Tells the person what a release did to the `.vibe` set — and, when automatic updates are off,
 * offers the one gesture that applies it
 *
 * Why a separate notice from [SeedConflictNotifier]: an untouched older revision is no decision,
 * it is news. The conflict notice is a question with five answers; mixing the two would bury the
 * question under a list of files that need no answer at all
 */
object SeedUpdateNotifier {
  private const val GROUP = com.vibe.agent.ui.VibeNotifications.AGENT

  /**
   * @param applied files this run has already refreshed — the list is shown, nothing to press;
   * @param pending files left alone because the setting is off — an update action applies them
   */
  fun notify(
    project: Project,
    projectBase: String,
    applied: List<VibeDefaults.SeedUpdate>,
    pending: List<VibeDefaults.SeedUpdate>,
  ) {
    if (applied.isNotEmpty()) {
      show(project, t("seed.update.done.title", "count" to applied.size), applied)
    }
    if (pending.isEmpty()) return
    val notification = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
      .createNotification(
        t("seed.update.available.title", "count" to pending.size),
        t("seed.update.available.body", "files" to pending.joinToString("\n") { it.describe() }),
        NotificationType.INFORMATION,
      )
    notification.addAction(NotificationAction.createSimple(t("seed.update.apply")) {
      ApplicationManager.getApplication().executeOnPooledThread {
        val report = VibeDefaults.seed(projectBase, autoUpdate = true)
        val vibeDir = Path.of(projectBase, ".vibe")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(vibeDir)?.refresh(false, true)
        ApplicationManager.getApplication().invokeLater {
          if (project.isDisposed) return@invokeLater
          show(project, t("seed.update.done.title", "count" to report.updated.size), report.updated)
          project.messageBus.syncPublisher(ProvidersChangeListener.TOPIC).providersChanged()
        }
      }
      notification.expire()
    })
    notification.notify(project)
  }

  private fun show(project: Project, title: String, updates: List<VibeDefaults.SeedUpdate>) {
    if (updates.isEmpty()) return
    NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
      .createNotification(
        title,
        updates.joinToString("\n") { it.describe() },
        NotificationType.INFORMATION,
      ).notify(project)
  }
}
