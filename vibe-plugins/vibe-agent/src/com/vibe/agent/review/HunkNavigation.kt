// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.review

/**
 * Where the reader's place goes in a list of hunks
 *
 * The place is a number in the list, never an id: every change of the text recomputes the hunks of a file and gives them new ones
 * Shared with VibeIDE through `testVectors/diffNavigation.json`, so the two products walk a review the same way
 */
object HunkNavigation {
  /**
   * Which change becomes current after one was accepted or rejected
   *
   * The one that followed the resolved change takes the turn, after the last one it is the one before it
   * Whatever was current before does not matter: the reader is where they just clicked, and that is where they continue
   *
   * @param resolvedIdx where the resolved change stood in the list before the resolve
   * @param newLength how many changes are left
   */
  fun afterResolve(resolvedIdx: Int, newLength: Int): Int? =
    if (newLength == 0) null else minOf(resolvedIdx.coerceAtLeast(0), newLength - 1)

  /** The same place in a list that changed under the reader: kept while it exists, else the last one; null for an empty list */
  fun clamp(idx: Int?, length: Int): Int? = when {
    length == 0 -> null
    idx == null -> 0
    else -> idx.coerceIn(0, length - 1)
  }

  /** One step along the list, round the end: past the last change is the first, before the first is the last */
  fun step(idx: Int?, length: Int, forward: Boolean): Int? {
    if (length == 0) return null
    if (idx == null) return if (forward) 0 else length - 1
    return Math.floorMod(idx + if (forward) 1 else -1, length)
  }

  /**
   * Whether a change spanning [top]..[bottom] has to be scrolled into the viewport [viewTop]..[viewBottom] (all in pixels)
   *
   * A change that is already in view stays where it is: the screen does not move under a reader who is looking at it
   * A change taller than the viewport can never fit whole, so it counts as in view once its top is
   */
  fun needsScroll(top: Int, bottom: Int, viewTop: Int, viewBottom: Int): Boolean {
    val taller = bottom - top > viewBottom - viewTop
    return if (taller) top < viewTop || top >= viewBottom else top < viewTop || bottom > viewBottom
  }
}
