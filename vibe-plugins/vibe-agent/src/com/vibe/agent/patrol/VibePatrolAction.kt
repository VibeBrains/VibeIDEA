// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.patrol

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.vibe.agent.i18n.VibeI18n.t
import java.nio.file.Files
import java.nio.file.Path

/**
 * «Дежурные проверки: прогнать сейчас» — ответ на вопрос «а моя проба вообще работает».
 *
 * Без этого действия первую настройку проверяют ожиданием: написал запись, подождал пятнадцать
 * минут, ничего не пришло — и непонятно, проба молчит или файл не читается вовсе. Поэтому здесь
 * показывается ВЕСЬ разбор: сколько записей, что в них не так и что нашла каждая проба.
 */
class VibePatrolAction : AnAction({ t("patrol.action") }) {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val base = project.basePath ?: return
    val text = runCatching { Files.readString(Path.of(base, Patrol.FILE)) }.getOrNull()
    if (text == null) {
      Messages.showInfoMessage(project, t("patrol.noFile", "file" to Patrol.FILE), t("patrol.action"))
      return
    }
    val parsed = Patrol.parse(text)
    val problems = parsed.problems.joinToString("\n") { problem ->
      t("patrol.problem", "where" to problem.where, "what" to when (problem.trouble) {
        Patrol.Trouble.NOT_AN_OBJECT -> t("patrol.trouble.notAnObject")
        Patrol.Trouble.NO_ID -> t("patrol.trouble.noId")
        Patrol.Trouble.NO_PROBE -> t("patrol.trouble.noProbe")
        Patrol.Trouble.DUPLICATE_ID -> t("patrol.trouble.duplicateId")
        Patrol.Trouble.BAD_INTERVAL -> t("patrol.trouble.badInterval",
                                         "min" to Patrol.MIN_MINUTES, "max" to Patrol.MAX_MINUTES)
      })
    }
    // Пробы — чужие команды: на EDT их запускать нельзя, интерфейс встанет на время самой медленной.
    ApplicationManager.getApplication().executeOnPooledThread {
      val found = PatrolService.getInstance(project).runNow()
      ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed) return@invokeLater
        val header = t("patrol.report", "total" to parsed.entries.size,
                       "active" to parsed.entries.count { it.active },
                       "found" to found.size)
        val body = buildString {
          append(header)
          if (found.isNotEmpty()) append("\n\n" + t("patrol.reportFound", "names" to found.joinToString()))
          if (problems.isNotEmpty()) append("\n\n" + problems)
        }
        Messages.showInfoMessage(project, body, t("patrol.action"))
      }
    }
  }
}
