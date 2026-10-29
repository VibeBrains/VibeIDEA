// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The working line always stands after the last card of the feed, right above the composer. */
class WorkingLineOrderTest {
  @Test
  fun `an empty feed puts the line at its only position`() {
    assertEquals(0, WorkingLineOrder.targetIndex(rowsWithoutLine = 0, currentIndex = null))
  }

  @Test
  fun `a line that is not in the feed yet goes after every row`() {
    assertEquals(4, WorkingLineOrder.targetIndex(rowsWithoutLine = 4, currentIndex = null))
  }

  @Test
  fun `a line already closing the feed is not moved`() {
    assertNull(WorkingLineOrder.targetIndex(rowsWithoutLine = 4, currentIndex = 4))
  }

  @Test
  fun `a card that arrived after the line pushes it down`() {
    assertEquals(5, WorkingLineOrder.targetIndex(rowsWithoutLine = 5, currentIndex = 4))
  }

  @Test
  fun `a line left above the person's bubble goes after it`() {
    // The turn starts with the line and only then draws the bubble: the line must end up below the question.
    assertEquals(2, WorkingLineOrder.targetIndex(rowsWithoutLine = 2, currentIndex = 0))
  }
}
