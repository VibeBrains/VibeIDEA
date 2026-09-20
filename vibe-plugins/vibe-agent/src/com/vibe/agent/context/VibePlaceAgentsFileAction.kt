// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.vibe.agent.i18n.VibeI18n.t
import java.nio.file.Files
import java.nio.file.Path

/**
 * Положить `AGENTS.md` в корень проекта из шаблона набора.
 *
 * Почему действием, а не сеялкой. Шаблон засевается в `.vibe/AGENTS.template.md`, а сам `AGENTS.md`
 * лежит в корне репозитория и уезжает в коммит человека. Создавать файл в чужом репозитории без
 * спроса мы не вправе: это не наша папка настроек, это его история. Поэтому набор кладёт заготовку
 * рядом, а в корень её переносит человек — одним действием, когда сам захотел.
 *
 * Существующий `AGENTS.md` не перезаписывается никогда: в нём уже могут быть правила, которых нет
 * больше нигде.
 */
class VibePlaceAgentsFileAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val base = project.basePath?.let { Path.of(it) } ?: return
    val target = base.resolve(ProjectRules.AGENTS_FILE)
    if (Files.exists(target)) {
      Messages.showInfoMessage(project, t("agents.place.exists"), t("agents.place.title"))
      return
    }
    val template = base.resolve(VIBE_DIR).resolve(TEMPLATE)
    val text = runCatching { Files.readString(template) }.getOrNull()
    if (text == null) {
      Messages.showWarningDialog(project, t("agents.place.noTemplate", "path" to "$VIBE_DIR/$TEMPLATE"), t("agents.place.title"))
      return
    }
    val written = runCatching { Files.writeString(target, text) }
    written.onFailure {
      Messages.showErrorDialog(project, t("agents.place.failed", "reason" to (it.message ?: it.javaClass.simpleName)),
                               t("agents.place.title"))
      return
    }
    // Без обновления виртуальной файловой системы файл не появится в дереве проекта до фокуса
    // окна: человек решит, что действие ничего не сделало.
    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
    Messages.showInfoMessage(project, t("agents.place.done", "name" to ProjectRules.AGENTS_FILE), t("agents.place.title"))
  }

  private companion object {
    const val VIBE_DIR = ".vibe"
    const val TEMPLATE = "AGENTS.template.md"
  }
}
