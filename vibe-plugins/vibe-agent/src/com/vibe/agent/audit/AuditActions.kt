// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
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
    title = "Журнал аудита агента"
    area.text = "Загрузка…"
    setOKButtonText("Закрыть")
    init()
    reload()
  }

  /** Load the tail OFF the EDT (disk IO), then marshal the text back. */
  private fun reload() {
    com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
      val lines = log.readRecent(RECENT_LIMIT)
      com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
        area.text = if (lines.isEmpty()) "Журнал пуст (или аудит выключен: Settings → Tools → VibeIDEA → Агент)."
                    else lines.joinToString("\n")
      }
    }
  }

  override fun createCenterPanel(): JComponent = JBScrollPane(area).apply {
    preferredSize = Dimension(JBUI.scale(760), JBUI.scale(460))
  }

  // Left-aligned extra buttons: Export + Clear.
  override fun createLeftSideActions(): Array<Action> = arrayOf(
    object : DialogWrapperAction("Экспортировать…") {
      override fun doAction(e: java.awt.event.ActionEvent?) {
        val descriptor = FileSaverDescriptor("Экспорт журнала аудита", "Сохранить .vibe/audit.jsonl в файл", "jsonl")
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
          .save(null as java.nio.file.Path?, "vibeidea-audit.jsonl")
        if (wrapper != null) {
          val target = wrapper.file.toPath()
          com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val err = runCatching { log.exportTo(target) }.exceptionOrNull()
            if (err != null) com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
              Messages.showErrorDialog(project, "Не удалось экспортировать: ${err.message}", "Аудит")
            }
          }
        }
      }
    },
    object : DialogWrapperAction("Очистить журнал") {
      override fun doAction(e: java.awt.event.ActionEvent?) {
        val confirm = Messages.showYesNoDialog(project,
          "Удалить весь журнал аудита (.vibe/audit.jsonl и сжатые сегменты)? Отменить нельзя.",
          "Очистка журнала аудита", "Удалить", "Отмена", Messages.getWarningIcon())
        if (confirm == Messages.YES) {
          com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val n = log.deleteAll()
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater { area.text = "Удалено файлов: $n. Журнал пуст." }
          }
        }
      }
    },
  )
}
