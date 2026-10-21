// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui.history

/** Height of the expanded landing history list: it scrolls inside a bounded area instead of pushing the panel out. */
object ThreadListBounds {
  /** Share of the panel height the expanded list may take. */
  const val MAX_SHARE = 0.6
  const val MIN_HEIGHT = 120

  fun maxHeight(panelHeight: Int): Int = maxOf(MIN_HEIGHT, (panelHeight * MAX_SHARE).toInt())

  fun height(contentHeight: Int, panelHeight: Int): Int = minOf(contentHeight, maxHeight(panelHeight))
}
