// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

/** What opening an agent session ahead of the first prompt may do right now — pure, so the guards are testable. */
internal object SessionAhead {
  enum class Action { SKIP, SHOW_EMPTY, START }

  /**
   * Decides for [threadId] after it became the open thread (or an agent target was picked).
   * Nothing starts without an agent target, while a turn runs, or while another start is still in flight.
   * During a turn only a foreign thread gets its pickers cleared: the running session must stay current.
   */
  fun decide(agentTargetSelected: Boolean, turnInFlight: Boolean, turnThreadId: String?, threadId: String, startInProgress: Boolean): Action = when {
    !agentTargetSelected -> Action.SKIP
    turnInFlight -> if (threadId != turnThreadId) Action.SHOW_EMPTY else Action.SKIP
    startInProgress -> Action.SKIP
    else -> Action.START
  }

  /** The start result may be applied only while the thread is still the open one and the target is still the same agent. */
  fun stillRelevant(openThreadId: String, threadId: String, targetIsSameAgent: Boolean): Boolean =
    openThreadId == threadId && targetIsSameAgent
}
