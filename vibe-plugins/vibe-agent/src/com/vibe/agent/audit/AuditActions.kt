// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

import com.vibe.agent.i18n.VibeI18n.t
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.nio.file.Path
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JTextArea

/**
 * Command-palette actions over `.vibe/audit.jsonl`: view the recent tail, export
 * the whole log, and erase it (GDPR). A standalone [AuditLog] instance is enough —
 * read/export/delete all address the same file path regardless of which panel wrote it.
 */
private const val RECENT_LIMIT = 300

/** «Журнал аудита агента» — shows the recent tail with Export / Clear. */
class VibeAuditLogAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
  override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    // Shared project-scoped log, so read/export/delete serialize with the panel's writes.
    val log = VibeAuditService.getInstance(project).get() ?: return
    AuditViewerDialog(project, log).show()
  }
}

private class AuditViewerDialog(private val project: Project, private val log: AuditLog) : DialogWrapper(project) {
  private val area = JTextArea().apply {
    isEditable = false
    // HiDPI-scaled like the other monospace surfaces (terminal/code blocks).
    font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, com.intellij.util.ui.JBUI.scaleFontSize(12f))
  }

  init {
    title = t("auditAction.title")
    area.text = t("auditAction.loading")
    setOKButtonText(t("common.close"))
    init()
    reload()
  }

  /** Load the tail OFF the EDT (disk IO), then marshal the text back. */
  private fun reload() {
    com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
      val lines = log.readRecent(RECENT_LIMIT)
      com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
        area.text = if (lines.isEmpty()) t("auditAction.empty")
                    else lines.joinToString("\n")
      }
    }
  }

  override fun createCenterPanel(): JComponent = com.vibe.agent.ui.VibeScroll.pane(area).apply {
    preferredSize = Dimension(JBUI.scale(760), JBUI.scale(460))
  }

  // Left-aligned extra buttons: Summary + Verify + Export + Clear.
  override fun createLeftSideActions(): Array<Action> = arrayOf(
    object : DialogWrapperAction(t("auditAction.summary")) {
      override fun doAction(e: java.awt.event.ActionEvent?) {
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
          // Reads the same tail the viewer shows: a summary of records the person cannot scroll to
          // would answer a different question from the one they are looking at.
          val report = AuditSummary.of(log.readRecent(RECENT_LIMIT))
          val verdict = log.verifyChain()
          val text = buildString {
            appendLine(t("auditAction.summaryTotal", "total" to report.total))
            if (report.unattributed > 0) appendLine(t("auditAction.summaryUnattributed", "count" to report.unattributed))
            appendLine()
            for (row in report.rows) {
              appendLine(t("auditAction.summaryRow",
                           "actor" to (row.actor + (row.role?.let { " ($it)" } ?: "")),
                           "records" to row.records, "failures" to row.failures))
              appendLine("    " + row.actions.joinToString(", "))
            }
            appendLine()
            append(
              when {
                verdict.brokenAtLine != null -> t("auditAction.chainBroken", "line" to verdict.brokenAtLine)
                verdict.unlinkedAtLine != null -> t("auditAction.chainPartial", "line" to verdict.unlinkedAtLine)
                else -> t("auditAction.chainIntact", "count" to verdict.checked)
              }
            )
          }
          com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater { area.text = text }
        }
      }
    },
    object : DialogWrapperAction(t("auditAction.export")) {
      override fun doAction(e: java.awt.event.ActionEvent?) {
        val descriptor = FileSaverDescriptor(t("auditAction.exportTitle"), t("auditAction.exportPrompt"), "jsonl")
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
          .save(null as java.nio.file.Path?, "vibeidea-audit.jsonl")
        if (wrapper != null) {
          val target = wrapper.file.toPath()
          com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val err = runCatching { log.exportTo(target) }.exceptionOrNull()
            if (err != null) com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
              Messages.showErrorDialog(project, t("auditAction.exportFailed", "reason" to err.message), t("auditAction.short"))
            }
          }
        }
      }
    },
    object : DialogWrapperAction(t("auditAction.clear")) {
      override fun doAction(e: java.awt.event.ActionEvent?) {
        val confirm = Messages.showYesNoDialog(project,
          t("auditAction.clearConfirm"),
          t("auditAction.clearTitle"), t("common.delete"), t("common.cancel"), Messages.getWarningIcon())
        if (confirm == Messages.YES) {
          com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val n = log.deleteAll()
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater { area.text = t("auditAction.cleared", "count" to n) }
          }
        }
      }
    },
  )
}
