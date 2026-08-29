// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages

/**
 * Checks every skill package of the project and reports the verdict in one place.
 *
 * Without it a broken skill is invisible: the popup lists it, `/skill:` inserts the token, and the
 * only symptom is an answer that is a little worse than it should have been.
 */
class VibeValidateSkillsAction : AnAction("VibeIDEA: проверить скиллы проекта") {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    ApplicationManager.getApplication().executeOnPooledThread {
      val entries = SkillsStore.list(project.basePath)
      val report = render(entries)
      ApplicationManager.getApplication().invokeLater {
        if (entries.any { it.isBroken }) Messages.showWarningDialog(project, report, "Скиллы проекта")
        else Messages.showInfoMessage(project, report, "Скиллы проекта")
      }
    }
  }

  private fun render(entries: List<SkillsStore.Entry>): String {
    if (entries.isEmpty()) {
      return "В проекте нет скиллов.\n\nОжидаются в ${SkillPackage.SKILLS_DIR}/<id>/${SkillPackage.SKILL_FILE}.\n" +
             "Формат — docs/vibe/manuals/skillsSpec.md."
    }
    val errors = entries.count { it.isBroken }
    val warnings = entries.count { !it.isBroken && it.findings.isNotEmpty() }
    return buildString {
      append("Проверено пакетов: ${entries.size}")
      append("; с ошибками: $errors, с предупреждениями: $warnings.\n")
      for (entry in entries) {
        if (entry.findings.isEmpty()) continue
        append("\n").append(entry.pkg.id).append(":\n")
        for (finding in entry.findings) {
          append(if (finding.level == SkillValidator.Level.ERROR) "  ✖ " else "  ⚠ ").append(finding.message).append("\n")
        }
      }
      if (errors == 0 && warnings == 0) append("\nВсе пакеты в порядке.")
      else append("\nПакет с ✖ не отправляется модели: /skill: скажет об этом в ленте.")
    }
  }
}
