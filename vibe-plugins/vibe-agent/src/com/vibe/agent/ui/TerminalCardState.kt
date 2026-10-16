// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

/**
 * Collapse state of one terminal card in the feed.
 *
 * A running command is shown expanded, a finished one folds away (success or failure alike),
 * so a long session does not flood the feed with output
 * A manual toggle is the user's decision: from then on the card never moves on its own
 * A card born already finished (restored history) starts collapsed
 */
class TerminalCardState(finished: Boolean = false) {
  var collapsed: Boolean = finished
    private set
  private var userChose = false

  /** The command ended: fold the card unless the user already took control of it. */
  fun onFinished() {
    if (!userChose) collapsed = true
  }

  /** Header click. */
  fun toggle() {
    userChose = true
    collapsed = !collapsed
  }
}
