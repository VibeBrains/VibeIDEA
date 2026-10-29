// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

/**
 * Place of the working line in the feed: the tail, right above the composer.
 *
 * The line is not a row of the turn but the feed's last row: cards, consoles and notices arrive while the turn runs,
 * and each arrival pushes the line down. The line disappears when nothing follows it, because that means the turn ended.
 * Pure, so the order is tested without an EDT or a panel.
 */
internal object WorkingLineOrder {
  /**
   * Index the working line takes among [rowsWithoutLine] rows that do not include it: the very end of the feed.
   *
   * [currentIndex] is the line's place in the feed now, or null when the line is not in the feed yet.
   * Null comes back when the line already closes the feed: nothing moves, and the component tree stays untouched.
   */
  fun targetIndex(rowsWithoutLine: Int, currentIndex: Int?): Int? =
    if (currentIndex != null && currentIndex == rowsWithoutLine) null else rowsWithoutLine
}
