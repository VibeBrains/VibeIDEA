// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.review

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.ui.composer.PillButton
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JPanel

/**
 * The strip under the editor of a file under review: where the reader is, and the moves that leave the file or settle it
 *
 * What it shows is pushed in by [show]; what the buttons do is the session's business, handed in as callbacks
 */
internal class ReviewBar(
  private val onPreviousHunk: () -> Unit,
  private val onNextHunk: () -> Unit,
  private val onPreviousFile: () -> Unit,
  private val onNextFile: () -> Unit,
  private val onAcceptFile: () -> Unit,
  private val onRejectFile: () -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(GAP))) {
  private val hunkLabel = JBLabel()
  private val fileLabel = JBLabel()
  private val previousHunk = button(AllIcons.Actions.PreviousOccurence, t("review.hunk.previous"), PREVIOUS_ACTION) { onPreviousHunk() }
  private val nextHunk = button(AllIcons.Actions.NextOccurence, t("review.hunk.next"), NEXT_ACTION) { onNextHunk() }
  private val previousFile = button(AllIcons.Actions.Back, t("review.file.previous"), null) { onPreviousFile() }
  private val nextFile = button(AllIcons.Actions.Forward, t("review.file.next"), null) { onNextFile() }

  init {
    background = UIUtil.getPanelBackground()
    border = BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border())
    add(previousHunk)
    add(hunkLabel)
    add(nextHunk)
    add(separator())
    add(previousFile)
    add(fileLabel)
    add(nextFile)
    add(separator())
    add(PillButton(text = t("review.file.accept"), outlined = true) { onAcceptFile() }.apply {
      foreground = ReviewColors.accept
      toolTipText = t("review.file.accept")
    })
    add(PillButton(text = t("review.file.reject"), outlined = true) { onRejectFile() }.apply {
      foreground = ReviewColors.reject
      toolTipText = t("review.file.reject")
    })
  }

  /** [place] is the zero-based hunk the reader is on, [hunks] how many there are; [file] likewise among [files] */
  fun show(place: Int?, hunks: Int, file: Int, files: Int) {
    hunkLabel.text = t("review.hunk.position", "current" to ((place ?: -1) + 1), "total" to hunks)
    fileLabel.text = t("review.file.position", "current" to (file + 1), "total" to files)
    previousFile.isVisible = files > 1
    nextFile.isVisible = files > 1
    fileLabel.isVisible = files > 1
    revalidate()
    repaint()
  }

  private fun separator() = JBLabel("|").apply { foreground = JBColor.border() }

  /** The tooltip names the key the action is on, so the strip teaches the keyboard that does the same */
  private fun button(icon: Icon, tooltip: String, actionId: String?, onClick: () -> Unit): PillButton =
    PillButton(icon = icon) { onClick() }.apply {
      val shortcut = actionId?.let { ActionManager.getInstance().getAction(it) }?.let { KeymapUtil.getFirstKeyboardShortcutText(it) }
      toolTipText = if (shortcut.isNullOrEmpty()) tooltip else "$tooltip ($shortcut)"
    }

  private companion object {
    const val GAP = 4
    const val PREVIOUS_ACTION = "Vibe.Review.PreviousHunk"
    const val NEXT_ACTION = "Vibe.Review.NextHunk"
  }
}
