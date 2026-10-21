// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import com.vibe.agent.ui.history.ThreadListBounds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FeedAutoscrollTest {
  @Test
  fun `middle of the viewport does not scroll`() {
    assertEquals(0, FeedAutoscroll.step(300, 600))
  }

  @Test
  fun `top edge scrolls up and bottom edge scrolls down`() {
    assertTrue(FeedAutoscroll.step(5, 600) < 0)
    assertTrue(FeedAutoscroll.step(595, 600) > 0)
  }

  @Test
  fun `farther past the edge is faster and capped`() {
    val near = FeedAutoscroll.step(20, 600)
    val far = FeedAutoscroll.step(-60, 600)
    assertTrue(-far > -near)
    assertEquals(-FeedAutoscroll.MAX_STEP, FeedAutoscroll.step(-5000, 600))
    assertEquals(FeedAutoscroll.MAX_STEP, FeedAutoscroll.step(5000, 600))
  }

  @Test
  fun `empty viewport never scrolls`() {
    assertEquals(0, FeedAutoscroll.step(10, 0))
  }

  @Test
  fun `shift extends only with anchor and selection`() {
    assertTrue(FeedSelectionModel.extendsOnShift(true, true, true))
    assertFalse(FeedSelectionModel.extendsOnShift(false, true, true))
    assertFalse(FeedSelectionModel.extendsOnShift(true, false, true))
    assertFalse(FeedSelectionModel.extendsOnShift(true, true, false))
  }

  @Test
  fun `expanded list is bounded by share of the panel and by its content`() {
    assertEquals(600, ThreadListBounds.maxHeight(1000))
    assertEquals(ThreadListBounds.MIN_HEIGHT, ThreadListBounds.maxHeight(0))
    assertEquals(600, ThreadListBounds.height(5000, 1000))
    assertEquals(200, ThreadListBounds.height(200, 1000))
  }
}
