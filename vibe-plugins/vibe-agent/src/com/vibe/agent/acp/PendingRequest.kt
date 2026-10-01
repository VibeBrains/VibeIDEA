// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.DialogWrapper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A request of the agent the client is still answering: a question to the person, a form, a write preview
 *
 * It can end before the person answers, two ways:
 * - the agent withdraws it with `$/cancel_request`, and the client answers `-32800` at once ([cancelledByAgent])
 * - the person presses Stop, and an open question closes as refused — the answer then goes out as usual
 * Either way a dialog left open would wait for nobody, so whoever shows one registers how to close it ([onDismiss])
 */
class PendingRequest(val sessionId: String?) {
  private val closers = CopyOnWriteArrayList<() -> Unit>()

  /** Closed before an answer: by the agent or by Stop; a handler that sees it answers as if refused */
  @Volatile var dismissed: Boolean = false
    private set

  /** Withdrawn by the agent: it has its `-32800` already, and the handler's answer goes nowhere */
  @Volatile var cancelledByAgent: Boolean = false
    private set

  /** [close] runs when the request is dismissed; at once when it already is */
  fun onDismiss(close: () -> Unit) {
    closers += close
    if (dismissed) close()
  }

  /**
   * [dialog] closes with [exitCode] when the request is dismissed; register before showing it
   * The close goes through the event queue in any modality: the dialog itself is what holds the modal loop
   */
  fun closes(dialog: DialogWrapper, exitCode: Int = DialogWrapper.CANCEL_EXIT_CODE) = onDismiss {
    ApplicationManager.getApplication().invokeLater({ if (!dialog.isDisposed) dialog.close(exitCode) }, ModalityState.any())
  }

  internal fun dismiss() {
    dismissed = true
    closers.forEach { runCatching(it) }
  }

  internal fun cancelByAgent() {
    cancelledByAgent = true
    dismiss()
  }
}
