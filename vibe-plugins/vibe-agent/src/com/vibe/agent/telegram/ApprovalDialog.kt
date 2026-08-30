// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.telegram

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

/**
 * The permission question, asked in two places at once.
 *
 * The dialog is the desktop half; [PendingApprovals] is the other. Whichever answers first closes
 * the other, so a run stopped by a destructive command can be released from the phone without
 * leaving a dialog nobody will ever click.
 */
object ApprovalDialog {
  /**
   * Blocks until answered — from here or from the phone. Must be called on the EDT, like any modal.
   *
   * The phone's answer is noticed by polling rather than by a callback: the future completes on a
   * network thread, and touching a modal dialog from there is how a UI freezes.
   */
  fun ask(
    project: Project,
    title: String,
    body: String,
    request: PendingApprovals.Request,
    alsoOnPhone: Boolean,
    /** Wording of the confirming button: «Выполнить» for a command says more than «Разрешить». */
    okText: String = t("telegram.button.approve"),
  ): Boolean {
    val dialog = object : DialogWrapper(project, true) {
      init {
        this.title = title
        setOKButtonText(okText)
        setCancelButtonText(t("telegram.button.deny"))
        init()
      }

      override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(8)
        add(JBLabel("<html>" + body.replace("\n", "<br>") + "</html>"), BorderLayout.CENTER)
        if (alsoOnPhone) add(JBLabel(t("telegram.alsoAsked")).apply { foreground = com.intellij.ui.JBColor.GRAY },
                             BorderLayout.SOUTH)
      }
    }
    val poll = Timer(POLL_MS) {
      if (request.answer.isDone) dialog.close(if (request.answer.get()) DialogWrapper.OK_EXIT_CODE else DialogWrapper.CANCEL_EXIT_CODE)
    }.apply { isRepeats = true; start() }
    val answeredHere = try {
      dialog.showAndGet()
    }
    finally {
      poll.stop()
    }
    // The phone's answer, if it arrived, is the authority — the dialog was closed BY it.
    if (request.answer.isDone) return request.answer.get()
    PendingApprovals.close(request.id)
    return answeredHere
  }

  private const val POLL_MS = 300
}
