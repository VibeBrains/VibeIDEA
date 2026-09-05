// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.features

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.ui.VibeNotifications

/**
 * Один раз на версию говорит, что список возможностей существует.
 *
 * Возможность, о которой не знают, не существует — а спрятана она обычно не глубоко, просто никто
 * не смотрел. Раз на версию, а не при каждом запуске: напоминание, приходящее каждый день,
 * закрывают не читая, и вместе с ним закрывают всё остальное.
 */
class FeaturesStartup : ProjectActivity {
  override suspend fun execute(project: Project) {
    val version = ApplicationInfo.getInstance().fullVersion
    val properties = PropertiesComponent.getInstance()
    if (properties.getValue(KEY_SHOWN_FOR) == version) return
    properties.setValue(KEY_SHOWN_FOR, version)
    NotificationGroupManager.getInstance()
      .getNotificationGroup(VibeNotifications.AGENT)
      .createNotification(t("features.notification.title"), t("features.notification.body"), NotificationType.INFORMATION)
      .addAction(NotificationAction.createSimpleExpiring(t("features.notification.open")) {
        VibeFeaturesAction.open(project)
      })
      .notify(project)
  }

  private companion object {
    const val KEY_SHOWN_FOR = "vibe.features.tourShownFor"
  }
}
