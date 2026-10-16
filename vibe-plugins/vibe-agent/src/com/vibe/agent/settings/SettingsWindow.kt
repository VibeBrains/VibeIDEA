// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.newEditor.SettingsDialog
import com.intellij.openapi.options.newEditor.SettingsNonModalDialog
import com.intellij.openapi.ui.DialogWrapper
import java.awt.Component
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities

/**
 * The settings window a page sits in, as far as getting out of its way goes
 *
 * The platform shows settings in one of two windows: a modal dialog, or a non-modal one that floats above the IDE (the default of
 * the platform this product is built on, switched by the advanced setting `ide.ui.non.modal.settings.window`)
 * Either one covers the editor, so a page that opens a file has to close it first, and the two are closed in different ways
 */
internal object SettingsWindow {
  /**
   * Closes the settings window that [from] sits in, and runs [then] once the window is gone
   *
   * The modal dialog closes with its pages applied, as OK does, so that nothing the person typed on another page is lost by a click
   * The non-modal window closes as its own close button does: a page that has unsaved changes is asked about by the window itself
   *
   * [then] does not run when the non-modal window is still there: it may refuse to close, and an editor opened much later,
   * long after the click, would be a surprise
   * Outside a settings window there is nothing to close, and [then] runs at once
   */
  fun closeThen(from: Component, then: () -> Unit) {
    val dialog = DialogWrapper.findInstance(from)
    val window = SwingUtilities.getWindowAncestor(from)
    when {
      dialog is SettingsDialog -> dialog.applyAndClose(false)
      dialog == null && window != null && SettingsNonModalDialog.isSettingsWindow(window) -> {
        window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
        if (window.isVisible) return
      }
      else -> {
        then()
        return
      }
    }
    // The runnable waits for the end of the dialog's modality: an editor opened while the dialog still stands cannot be typed into
    ApplicationManager.getApplication().invokeLater(then, ModalityState.nonModal())
  }
}
