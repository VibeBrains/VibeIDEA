// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.server

import com.intellij.ide.util.PropertiesComponent
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.server.ui.ServerPanel

/**
 * Поднимает стек при открытии проекта — если проект об этом просил и человек однажды согласился.
 *
 * Решение о том, можно ли, принимает [AutoStartPolicy]; здесь только окружение: чтение файла,
 * доверие проекту, память о согласии и запуск через ту же панель, что и кнопка «Запустить всё».
 *
 * Согласие спрашивается ОДИН раз на проект и помнится. Спрашивать каждое утро — не гейт, а
 * тренировка нажимать «да»; спросить единожды и запомнить — ровно то, что человек имел в виду,
 * когда писал `autoStart` в файле своего проекта.
 */
class AutoStartStartup : ProjectActivity {
  override suspend fun execute(project: Project) {
    val entries = ServersFile.load(project.basePath) { }
    val properties = PropertiesComponent.getInstance(project)
    val verdict = AutoStartPolicy.decide(
      entries = entries,
      trusted = TrustedProjects.isProjectTrusted(project),
      consent = AutoStartPolicy.consentOf(properties.getValue(KEY_CONSENT)),
    )
    when (verdict) {
      AutoStartPolicy.Verdict.START -> start(project)
      AutoStartPolicy.Verdict.ASK -> ask(project, properties, AutoStartPolicy.wanted(entries).size)
      // Молчим: недоверенный проект, отказ человека и пустой файл ничего не должны показывать.
      else -> {}
    }
  }

  private fun ask(project: Project, properties: PropertiesComponent, count: Int) {
    NotificationGroupManager.getInstance().getNotificationGroup(com.vibe.agent.ui.VibeNotifications.SERVER)
      .createNotification(t("servers.autoStart.ask", "count" to count), NotificationType.INFORMATION)
      .addAction(NotificationAction.createSimple(t("servers.autoStart.yes")) {
        properties.setValue(KEY_CONSENT, CONSENT_YES)
        start(project)
      })
      .addAction(NotificationAction.createSimple(t("servers.autoStart.no")) {
        properties.setValue(KEY_CONSENT, CONSENT_NO)
      })
      .notify(project)
  }

  /**
   * Панель создаётся лениво, поэтому окно показывается — иначе запускать было бы не через что.
   * Именно `show`, а не `activate`: фокус остаётся там, где человек его оставил, а вывод стека,
   * который сейчас поедет, обязан быть виден — стек, поднявшийся молча, невозможно отладить.
   */
  private fun start(project: Project) {
    ApplicationManager.getApplication().invokeLater {
      if (project.isDisposed) return@invokeLater
      val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(com.vibe.agent.ui.VibeToolWindows.SERVER) ?: return@invokeLater
      toolWindow.show {
        (toolWindow.contentManager.contents.firstOrNull()?.component as? ServerPanel)?.startAutoStart()
      }
    }
  }

  private companion object {
    /** По проекту, а не глобально: согласие поднимать ЭТОТ стек ничего не говорит о чужом. */
    const val KEY_CONSENT = "vibe.server.autoStart.consent"
    const val CONSENT_YES = "yes"
    const val CONSENT_NO = "no"
  }
}
