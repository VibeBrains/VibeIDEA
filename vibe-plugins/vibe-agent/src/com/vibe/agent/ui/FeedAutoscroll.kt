// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

/**
 * Speed of the feed's autoscroll while a selection is dragged to the viewport edge.
 *
 * Pure arithmetic: the mouse position in viewport coordinates in, pixels per tick out.
 * The position may lie outside the viewport (the button is held and the mouse left it): the farther, the faster.
 */
object FeedAutoscroll {
  const val TICK_MS = 30
  /** Zone inside the viewport, at each edge, where the scroll already starts. */
  const val EDGE_ZONE = 28
  const val MIN_STEP = 2
  const val MAX_STEP = 48
  /** Distance past the zone edge at which the speed reaches [MAX_STEP]. */
  private const val FULL_SPEED_DISTANCE = 120

  /** Negative scrolls up, positive scrolls down, zero is no scroll. */
  fun step(y: Int, viewportHeight: Int, zone: Int = EDGE_ZONE): Int {
    if (viewportHeight <= 0) return 0
    val into = when {
      y < zone -> zone - y
      y > viewportHeight - zone -> -(y - (viewportHeight - zone))
      else -> return 0
    }
    val magnitude = (MIN_STEP + (MAX_STEP - MIN_STEP) * minOf(Math.abs(into), FULL_SPEED_DISTANCE) / FULL_SPEED_DISTANCE)
    // into > 0 means the top edge
    return if (into > 0) -magnitude else magnitude
  }
}
