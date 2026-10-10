// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graphview

/**
 * Scale and shift of a graph as arithmetic, apart from drawing
 *
 * Zoom and panning break silently and alike: the picture slides off the edge, the wheel sticks at a limit
 * Fitting leaves half the graph off screen
 * None of it shows in a screenshot review and all of it shows in a test
 * So the counting lives here and the canvas only draws
 */
object GraphZoom {
  /** No closer: past this it is no longer a graph but one node over the whole screen */
  const val MAX = 3.0

  /** No farther: smaller captions stop being readable, and the picture lies about connectivity */
  const val MIN = 0.15

  /** A wheel step, multiplicative rather than additive so the step is the same at any scale */
  const val WHEEL_STEP = 1.1

  /** Margin around a fitted graph, in unscaled pixels */
  const val FIT_MARGIN = 24

  fun clamp(scale: Double): Double = scale.coerceIn(MIN, MAX)

  /**
   * Scale that fits the whole graph on screen
   *
   * With margins: a graph pressed to the very edges reads as cut off, and people reach for the mouse to move it away
   * One is the ceiling: stretching a small graph over the screen shows five pages in letters a finger high
   */
  fun fit(graphWidth: Int, graphHeight: Int, viewWidth: Int, viewHeight: Int, margin: Int = FIT_MARGIN): Double {
    if (graphWidth <= 0 || graphHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return 1.0
    val usableWidth = (viewWidth - margin * 2).coerceAtLeast(1)
    val usableHeight = (viewHeight - margin * 2).coerceAtLeast(1)
    val scale = minOf(usableWidth.toDouble() / graphWidth, usableHeight.toDouble() / graphHeight)
    return clamp(minOf(scale, 1.0))
  }

  /** The shift that puts the graph in the middle of the window at the given scale */
  fun center(graphWidth: Int, graphHeight: Int, viewWidth: Int, viewHeight: Int, scale: Double): Pair<Int, Int> =
    Pair(
      ((viewWidth - graphWidth * scale) / 2).toInt(),
      ((viewHeight - graphHeight * scale) / 2).toInt(),
    )

  /**
   * The new shift after a wheel zoom: the point under the cursor stays where it was
   *
   * Otherwise the graph runs away from the cursor and a person chases it after every click of the wheel
   */
  fun zoomAt(offset: Int, cursor: Int, oldScale: Double, newScale: Double): Int =
    (cursor - (cursor - offset) * (newScale / oldScale)).toInt()
}
