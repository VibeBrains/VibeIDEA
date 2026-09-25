// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.slop

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import com.vibe.agent.settings.VibeAgentSettings
import com.vibe.agent.i18n.VibeI18n.t

/**
 * The editor's text-slop action: the detector on the selection, or on the whole file when it is prose.
 *
 * The findings come as a list in the editor, most severe first; choosing one selects the text it matched, so the
 * person fixes the line rather than searching for it. A clean text answers with a hint and nothing else.
 *
 * The check runs in the background, cancellable: a whole document takes seconds,
 * And a rule from the project's `.vibe/slop.json` may run until its time limit ([SlopBudget])
 */
class VibeTextSlopAction : AnAction({ t("slop.action") }) {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val editor = e.getData(CommonDataKeys.EDITOR)
    val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
    e.presentation.isEnabledAndVisible = e.project != null && editor != null &&
                                         (editor.selectionModel.hasSelection() || file?.let { SlopCheck.isProse(it.name) } == true)
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return
    val selection = editor.selectionModel
    val text = if (selection.hasSelection()) selection.selectedText.orEmpty() else editor.document.text
    // Offsets of findings are inside the checked text; the selection's start brings them back into the document.
    val base = if (selection.hasSelection()) selection.selectionStart else 0
    val warnings = java.util.Collections.synchronizedList(ArrayList<String>())
    var report: SlopReport? = null
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, t("slop.action.progress"), true) {
      override fun run(indicator: ProgressIndicator) {
        try {
          report = SlopCheck.check(text, project.basePath, VibeAgentSettings.slopBudget { indicator.isCanceled }) { warnings.add(it) }
        }
        catch (e: java.util.concurrent.CancellationException) {
          // The detector stops with the plain JDK exception (it runs without the IDE too); the platform's own one
          // makes the task end as cancelled rather than as failed
          indicator.checkCanceled()
          throw e
        }
      }

      override fun onSuccess() {
        if (!editor.isDisposed) show(project, editor, base, report, warnings.toList())
      }
    })
  }

  private fun show(project: com.intellij.openapi.project.Project, editor: Editor, base: Int, report: SlopReport?,
                   warnings: List<String>) {
    if (report == null) {
      Messages.showErrorDialog(project, t("slop.cli.noCatalog", "reason" to SlopCheck.builtInWarnings.joinToString("; ")),
                               t("slop.action.title"))
      return
    }
    val verdict = SlopLabels.verdict(SlopRender.number(report.score), SlopRender.number(report.passScore), report.passed,
                                     report.findings.size)
    val skipped = report.skipped.takeIf { it.isNotEmpty() }?.let { SlopLabels.skipped(it) }
    if (report.findings.isEmpty()) {
      HintManager.getInstance().showInformationHint(editor, listOfNotNull(verdict, skipped).joinToString("\n"))
      return
    }
    val builder = JBPopupFactory.getInstance().createPopupChooserBuilder(SlopRender.ordered(report.findings))
      .setTitle(verdict)
      .setRenderer(SimpleListCellRenderer.create("") { SlopLabels.finding(it) })
      .setItemChosenCallback { select(editor, base + it.start, base + it.end) }
      .setNamerForFiltering { it.match + " " + it.name }
    val notes = warnings + listOfNotNull(skipped)
    if (notes.isNotEmpty()) builder.setAdText(t("slop.action.overrides", "text" to notes.joinToString("; ")))
    builder.createPopup().showInBestPositionFor(editor)
  }

  private fun select(editor: Editor, start: Int, end: Int) {
    val length = editor.document.textLength
    val from = start.coerceIn(0, length)
    val to = end.coerceIn(from, length)
    editor.caretModel.moveToOffset(from)
    editor.selectionModel.setSelection(from, to)
    editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
  }
}
