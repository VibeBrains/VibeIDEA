// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.acp

import com.vibe.agent.i18n.VibeI18n.t

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.JComponent

/**
 * The VibeIDE contract: every agent write is a QUESTION with a был→стало diff
 * BEFORE anything lands on disk; a closed dialog is a refusal, never a silent
 * allow. Returns true only on an explicit «Применить».
 */
object WritePreview {
  /** [request] withdrawn while the diff is open closes it as a refusal */
  fun confirm(project: Project, path: String, oldText: String, newText: String, request: PendingRequest?): Boolean {
    if (request?.dismissed == true) return false
    var approved = false
    ApplicationManager.getApplication().invokeAndWait {
      val dialog = object : DialogWrapper(project, true) {
        init {
          title = t("write.dialog.title", "path" to path)
          setOKButtonText(t("write.apply"))
          setCancelButtonText(t("write.reject"))
          init()
        }

        override fun createCenterPanel(): JComponent {
          val factory = DiffContentFactory.getInstance()
          val request = SimpleDiffRequest(
            path,
            factory.create(project, oldText),
            factory.create(project, newText),
            t("write.before"),
            t("write.after"),
          )
          val panel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
          panel.setRequest(request)
          return panel.component.apply {
            preferredSize = Dimension(JBUI.scale(860), JBUI.scale(520))
          }
        }
      }
      request?.closes(dialog)
      approved = dialog.showAndGet() && request?.dismissed != true
    }
    return approved
  }
}
