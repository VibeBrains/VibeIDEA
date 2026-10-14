// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which of the three layouts a feed width selects, and every margin in pixels
 *
 * The thresholds are inclusive and the panel's width is the only input, so the edges are asserted to the pixel:
 * exactly 360 and one wider, exactly 280 and one wider
 * Those are the widths a person will try by dragging the sash
 */
class ChatMessageLayoutTest {
  private fun layout(width: Int, scale: Float = 1f) = ChatMessageLayout.of(width, scale)

  @Test
  fun `a feed of 361 is wide and of exactly 360 is narrow`() {
    assertEquals(ChatLayoutMode.WIDE, layout(361).mode)
    assertEquals(ChatLayoutMode.NARROW, layout(360).mode)
  }

  @Test
  fun `a feed of 281 is narrow and of exactly 280 is tight`() {
    assertEquals(ChatLayoutMode.NARROW, layout(281).mode)
    assertEquals(ChatLayoutMode.TIGHT, layout(280).mode)
  }

  @Test
  fun `a width that is not known yet lays out wide with nothing to measure`() {
    val unknown = layout(0)
    assertEquals(ChatLayoutMode.WIDE, unknown.mode)
    assertEquals(0, unknown.userLeftMargin)
    assertEquals(0, unknown.assistantRightMargin)
  }

  @Test
  fun `thresholds follow the UI scale`() {
    // At 200% the logical 360 and 280 are 720 and 560 pixels
    assertEquals(ChatLayoutMode.NARROW, layout(720, 2f).mode)
    assertEquals(ChatLayoutMode.WIDE, layout(721, 2f).mode)
    assertEquals(ChatLayoutMode.TIGHT, layout(560, 2f).mode)
    assertEquals(ChatLayoutMode.NARROW, layout(561, 2f).mode)
  }

  @Test
  fun `wide - the reply has a 2 pixel rule, a 10 pixel gap and an 18 percent gutter`() {
    val wide = layout(500)
    assertEquals(2, wide.assistantRule)
    assertEquals(10, wide.assistantRuleGap)
    assertEquals(90, wide.assistantRightMargin)
  }

  @Test
  fun `wide - the request has a 22 percent margin on the left, an outline all round and a tail`() {
    val wide = layout(500)
    assertEquals(110, wide.userLeftMargin)
    assertEquals(BubbleRadii(14, 14, 4, 14), wide.radii)
    assertEquals(1, wide.outline)
    assertEquals(0, wide.bar)
    assertTrue(wide.timeInCorner)
    assertFalse(wide.captionAbove)
    assertFalse(wide.bubbleFillsRow)
  }

  @Test
  fun `narrow - the reply loses its rule, gap and gutter, the request keeps a 12 percent margin`() {
    val narrow = layout(360)
    assertEquals(0, narrow.assistantRule)
    assertEquals(0, narrow.assistantRuleGap)
    assertEquals(0, narrow.assistantRightMargin)
    assertEquals(43, narrow.userLeftMargin)
    assertEquals(BubbleRadii(14, 14, 4, 14), narrow.radii)
    assertTrue(narrow.timeInCorner)
  }

  @Test
  fun `tight - the bubble is a plate across the row with a bar on its left and the time in a caption`() {
    val tight = layout(280)
    assertEquals(0, tight.userLeftMargin)
    assertEquals(BubbleRadii(8, 8, 8, 8), tight.radii)
    assertEquals(0, tight.outline)
    assertEquals(3, tight.bar)
    assertTrue(tight.bubbleFillsRow)
    assertTrue(tight.captionAbove)
    assertFalse(tight.timeInCorner)
    assertEquals(0, tight.assistantRule)
  }

  @Test
  fun `the bubble's insets are its padding plus the border it draws`() {
    val wide = layout(500)
    assertEquals(13, wide.insetLeft)
    assertEquals(13, wide.insetRight)
    assertEquals(9, wide.insetTop)
    assertEquals(7, wide.insetBottom)
    val tight = layout(280)
    assertEquals(15, tight.insetLeft)
    assertEquals(12, tight.insetRight)
    assertEquals(8, tight.insetTop)
    assertEquals(6, tight.insetBottom)
  }

  @Test
  fun `the 720 pixel cap on a reply and the gutter do not count twice`() {
    // The text column of a reply ends at the row's width less its gutter
    // Whichever of the cap and the gutter reaches further in is the one that decides, never both added together
    for (width in 361..2400) {
      val rowWidth = min(width - 12, 720)
      val columnEnd = rowWidth - layout(width).assistantRightMargin
      val expected = min(720, width - 12 - Math.round(width * 18 / 100f))
      assertEquals(expected, columnEnd, "feed of $width")
    }
  }

  @Test
  fun `a bubble fits its text, keeps the room its margin leaves and a plate takes all of it`() {
    val wide = layout(500)
    // 100 px of text and a 2 px safety margin, plus 26 px of padding and border
    assertEquals(128, bubbleWidth(wide, naturalContentWidth = 100, available = 488 - wide.userLeftMargin))
    // A long line is held at what the row leaves after the margin
    assertEquals(378, bubbleWidth(wide, naturalContentWidth = 5_000, available = 488 - wide.userLeftMargin))
    val tight = layout(280)
    assertEquals(268, bubbleWidth(tight, naturalContentWidth = 10, available = 268))
  }

  @Test
  fun `no room left gives a bubble of no width, never a negative one`() {
    assertEquals(0, bubbleWidth(layout(500), naturalContentWidth = 100, available = -5))
  }

  @Test
  fun `the feed metrics report a change only when something a row draws has changed`() {
    val metrics = ChatFeedMetrics { 1f }
    assertEquals(ChatLayoutSpec.DEFAULT_FEED_WIDTH - 12, metrics.rowWidth(), "a feed not shown yet is taken to be 500 wide")
    assertFalse(metrics.update(0), "an unknown width changes nothing")
    assertEquals(ChatLayoutSpec.DEFAULT_FEED_WIDTH, metrics.feedWidth)
    assertFalse(metrics.update(500), "the width it already assumes")
    assertTrue(metrics.update(480), "margins are shares of the width, so a few pixels change them")
    assertEquals(468, metrics.rowWidth())
    assertFalse(metrics.update(480), "the same width again")
    // Inside the tight layout nothing depends on the width: the plate takes whatever the row is
    assertTrue(metrics.update(280))
    assertFalse(metrics.update(270))
    assertEquals(270, metrics.feedWidth)
  }
}
