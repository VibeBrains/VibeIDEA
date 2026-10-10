// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.review

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.vibe.agent.i18n.VibeI18n.t

/**
 * The keys of the review: next and previous hunk, accept and reject the current one
 *
 * Each works on the session of the editor the key was pressed in, and is off where there is no review or nothing left in it,
 * so the key reaches the editor as it always did
 * Accepting and rejecting do not step on afterwards: the session has already put the reader on the hunk that followed
 */
abstract class ReviewAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  protected abstract fun title(): String

  protected abstract fun perform(session: ReviewSession)

  protected fun sessionOf(e: AnActionEvent): ReviewSession? {
    val project = e.project ?: return null
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
    return AgentReviewService.getInstance(project).sessionFor(editor)
  }

  override fun update(e: AnActionEvent) {
    e.presentation.text = title()
    e.presentation.isEnabled = sessionOf(e)?.hasHunks == true
  }

  override fun actionPerformed(e: AnActionEvent) {
    sessionOf(e)?.takeIf { it.hasHunks }?.let { perform(it) }
  }
}

class VibeReviewNextAction : ReviewAction() {
  override fun title(): String = t("review.hunk.next")
  override fun perform(session: ReviewSession) = session.step(forward = true)
}

class VibeReviewPreviousAction : ReviewAction() {
  override fun title(): String = t("review.hunk.previous")
  override fun perform(session: ReviewSession) = session.step(forward = false)
}

class VibeReviewAcceptAction : ReviewAction() {
  override fun title(): String = t("review.hunk.accept")
  override fun perform(session: ReviewSession) = session.accept()
}

class VibeReviewRejectAction : ReviewAction() {
  override fun title(): String = t("review.hunk.reject")
  override fun perform(session: ReviewSession) = session.reject()
}
