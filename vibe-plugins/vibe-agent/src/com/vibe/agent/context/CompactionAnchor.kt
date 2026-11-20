// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.context

import com.vibe.agent.history.ChatMessageRecord
import com.vibe.agent.history.Role
import com.vibe.agent.i18n.VibeI18n.t

/**
 * What the model gets back after its history was folded into a summary: the sources, verbatim.
 *
 * A summariser asked to «keep what matters» drops the one line the work depends on — the person's own
 * wording of the task, and the decisions the project already made. Both are written down outside the
 * conversation, so the fold does not have to carry them: the original request and the decisions journal
 * travel whole after the summary, and the model re-reads its anchor instead of trusting a retelling.
 *
 * Pure: the records and the journal text in, the system message out. No file IO and no project.
 */
object CompactionAnchor {
  /**
   * The task as the person first wrote it: the first user message, not its inlined context blocks.
   *
   * The raw text is the anchor. Context blocks are a snapshot of what was open at that moment and
   * would be re-inlined on every turn after the fold, at full price.
   */
  fun taskOf(transcript: List<ChatMessageRecord>): String? = transcript
    .firstOrNull { it.role == Role.USER && !it.interim }
    ?.text
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

  /**
   * The anchor message, or null when there is nothing to anchor to.
   *
   * [alreadyKept] is what the fold kept verbatim (pinned records): repeating it would spend the window
   * on the same words twice.
   */
  fun text(task: String?, decisions: String?, alreadyKept: List<String> = emptyList()): String? {
    val kept = alreadyKept.map { it.trim() }.toSet()
    val taskText = task?.trim()?.takeIf { it.isNotEmpty() && it !in kept }
    val journal = decisions?.trim()?.takeIf { it.isNotEmpty() }
    if (taskText == null && journal == null) return null
    return buildString {
      appendLine(t("context.anchor.header"))
      taskText?.let { appendLine(); appendLine(t("context.anchor.task")); appendLine(it) }
      journal?.let { appendLine(); appendLine(t("context.anchor.decisions")); appendLine(it) }
    }.trimEnd()
  }
}
