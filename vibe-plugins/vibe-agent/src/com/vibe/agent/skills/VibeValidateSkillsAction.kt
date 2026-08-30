// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

import com.vibe.agent.i18n.VibeI18n.t

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
class VibeValidateSkillsAction : AnAction(t("skills.action.title")) {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    ApplicationManager.getApplication().executeOnPooledThread {
      val entries = SkillsStore.list(project.basePath)
      val report = render(entries)
      ApplicationManager.getApplication().invokeLater {
        if (entries.any { it.isBroken }) Messages.showWarningDialog(project, report, t("skills.dialog.title"))
        else Messages.showInfoMessage(project, report, t("skills.dialog.title"))
      }
    }
  }

  private fun render(entries: List<SkillsStore.Entry>): String {
    if (entries.isEmpty()) {
      return t("skills.report.none", "dir" to SkillPackage.SKILLS_DIR, "file" to SkillPackage.SKILL_FILE) + "\n" +
             t("skills.report.spec")
    }
    val errors = entries.count { it.isBroken }
    val warnings = entries.count { !it.isBroken && it.findings.isNotEmpty() }
    return buildString {
      append(t("skills.report.checked", "count" to entries.size))
      append(t("skills.report.counts", "errors" to errors, "warnings" to warnings) + "\n")
      for (entry in entries) {
        if (entry.findings.isEmpty()) continue
        append("\n").append(entry.pkg.id).append(":\n")
        for (finding in entry.findings) {
          append(if (finding.level == SkillValidator.Level.ERROR) "  ✖ " else "  ⚠ ").append(finding.message).append("\n")
        }
      }
      if (errors == 0 && warnings == 0) append("\n" + t("skills.report.allOk"))
      else append("\n" + t("skills.report.brokenSkipped"))
    }
  }
}
