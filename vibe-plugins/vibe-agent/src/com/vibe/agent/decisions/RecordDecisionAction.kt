// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.decisions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.ui.VibeScroll
import java.awt.Dimension
import javax.swing.JComponent

/**
 * «Зафиксировать решение» — единственный способ, которым журнал решений начинает существовать.
 *
 * Формат без действия остаётся недостижимым: писать ADR руками не будет никто, а решение созревает
 * в разговоре и там же умирает. Форма спрашивает ровно то, что нельзя восстановить из кода: вопрос,
 * выбор, отвергнутое и **почему** — последнее обязательно, иначе запись не отвечает на вопрос, ради
 * которого её потом открывают.
 */
class RecordDecisionAction : AnAction({ t("decisions.action") }) {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    record(project, question = "", chosen = "", why = "")
  }

  companion object {
    /**
     * Показывает форму с предзаполненными полями.
     *
     * Предзаполнение — то, ради чего это отдельная функция: решение чаще всего уже сформулировано
     * в ответе агента, и перенабирать его руками означает не зафиксировать ничего.
     */
    fun record(project: Project, question: String, chosen: String, why: String) {
      val store = DecisionStore.getInstance(project)
      val dialog = DecisionDialog(project, store.nextNumber(), question, chosen, why)
      if (!dialog.showAndGet()) return
      val decision = dialog.decision()
      val refusal = DecisionRecord.validate(decision)
      if (refusal != null) {
        Messages.showWarningDialog(project, when (refusal) {
          DecisionRecord.Refusal.NO_QUESTION -> t("decisions.refusal.noQuestion")
          DecisionRecord.Refusal.NO_CHOICE -> t("decisions.refusal.noChoice")
          DecisionRecord.Refusal.NO_REASON -> t("decisions.refusal.noReason")
        }, t("decisions.action"))
        return
      }
      val path = store.write(decision, t("decisions.index.header"))
      if (path == null) {
        Messages.showErrorDialog(project, t("decisions.writeFailed", "folder" to store.folder()), t("decisions.action"))
        return
      }
      // Файл на диске, но не в редакторе — это записка, которую никто не перечитал.
      val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
        .refreshAndFindFileByPath(project.basePath + "/" + path)
      if (file != null) com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(file, true)
    }
  }
}

private class DecisionDialog(
  project: Project,
  private val number: Int,
  question: String,
  chosen: String,
  why: String,
) : DialogWrapper(project) {
  private val questionField = JBTextField(question, 40)
  private val chosenField = JBTextArea(chosen, 3, 40)
  private val rejectedField = JBTextArea("", 3, 40)
  private val whyField = JBTextArea(why, 4, 40)
  private val linksField = JBTextField("", 40)

  init {
    title = t("decisions.dialog.title", "number" to number)
    setOKButtonText(t("decisions.dialog.ok"))
    init()
  }

  override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
    .addLabeledComponent(t("decisions.field.question"), questionField)
    .addLabeledComponent(t("decisions.field.chosen"), VibeScroll.pane(chosenField))
    .addLabeledComponent(t("decisions.field.rejected"), VibeScroll.pane(rejectedField))
    .addLabeledComponent(t("decisions.field.why"), VibeScroll.pane(whyField))
    .addLabeledComponent(t("decisions.field.links"), linksField)
    .addComponent(JBLabel("<html>" + t("decisions.dialog.hint") + "</html>").apply {
      foreground = com.intellij.ui.JBColor.GRAY
    })
    .panel
    .apply {
      border = JBUI.Borders.empty(8)
      preferredSize = Dimension(520, 380)
    }

  fun decision(): DecisionRecord.Decision = DecisionRecord.Decision(
    number = number,
    question = questionField.text.trim(),
    chosen = chosenField.text.trim(),
    rejected = rejectedField.text.trim(),
    why = whyField.text.trim(),
    date = java.time.LocalDate.now().toString(),
    links = linksField.text.split(',').map { it.trim() }.filter { it.isNotEmpty() },
  )
}
