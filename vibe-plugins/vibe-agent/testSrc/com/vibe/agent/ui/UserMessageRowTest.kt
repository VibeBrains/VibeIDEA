// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.JTextArea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The user's message laid out as a real component, headless: where the bubble sits in the row and how wide it is
 *
 * The arithmetic is covered by [ChatMessageLayoutTest]; here the components must do what the numbers say
 * That is the part a screenshot would show
 * The bubble hugs the right edge, fits a short message and stops at its margin for a long one
 * The plate takes the whole row
 */
class UserMessageRowTest {
  private fun prose(text: String) = JTextArea().apply {
    isEditable = false
    isOpaque = false
    lineWrap = true
    wrapStyleWord = true
    font = JBFont.label().deriveFont(13f)
    border = JBUI.Borders.empty()
    this.text = text
  }

  private class Fixture(val row: UserMessageRow, val bubble: UserBubble, val metrics: ChatFeedMetrics)

  private fun fixture(text: String, feedWidth: Int, chips: MessageChipStrip? = null): Fixture {
    val metrics = ChatFeedMetrics()
    metrics.update(feedWidth)
    val bubble = UserBubble(prose(text), "12:34", "You · 12:34", dateTooltip = null, metrics = metrics)
    val row = UserMessageRow(bubble, chips, metrics)
    row.size = Dimension(metrics.rowWidth(), 1)
    row.size = Dimension(metrics.rowWidth(), row.preferredSize.height)
    // No window, so no peer and no validate(): the two levels are laid out by hand, in the order validate() would
    row.doLayout()
    bubble.doLayout()
    return Fixture(row, bubble, metrics)
  }

  private val long = "word ".repeat(200).trim()

  @Test
  fun `the bubble hugs the right edge of the row`() {
    val f = fixture("hello", feedWidth = 500)
    val insets = f.row.insets
    assertEquals(f.row.width - insets.right, f.bubble.x + f.bubble.width)
    assertEquals(insets.top, f.bubble.y)
  }

  @Test
  fun `a short message makes a small bubble, far from the left margin`() {
    val f = fixture("hello", feedWidth = 500)
    val available = f.row.width - f.row.insets.left - f.row.insets.right
    assertTrue(f.bubble.width < available / 2, "bubble ${f.bubble.width} of $available")
    assertTrue(f.bubble.width > JBUI.scale(40), "a bubble must hold its text, got ${f.bubble.width}")
  }

  @Test
  fun `a long message stops at the margin and wraps instead of growing`() {
    val f = fixture(long, feedWidth = 500)
    val available = f.row.width - f.row.insets.left - f.row.insets.right
    assertEquals(available - f.metrics.layout.userLeftMargin, f.bubble.width)
    assertTrue(f.bubble.height > JBUI.scale(60), "a thousand characters in about 380 px cannot be one line, height ${f.bubble.height}")
  }

  @Test
  fun `a narrower feed wraps the same message into more lines`() {
    val wide = fixture(long, feedWidth = 700)
    val narrow = fixture(long, feedWidth = 400)
    assertTrue(narrow.bubble.height > wide.bubble.height, "${narrow.bubble.height} vs ${wide.bubble.height}")
  }

  @Test
  fun `the text is never cut at the bottom, at any width`() {
    // The text's own view answers a line short at about one width in thirty, and the last line of the message is cut
    // Those are widths a person reaches by dragging the sash, and a single width proves nothing, so every width is tried
    val url = "Please look at the retry policy: the pauses should be 1, 2 and 4 seconds, and a Retry-After longer than half a minute " +
              "must stop the turn. https://example.com/a/very/long/link/that/does/not/break/anywhere/at/all/ever"
    val lines = "hello\nthe second line is a bit longer than the first one, so that it wraps in the narrow bubbles of a chat"
    for (text in listOf(url, lines, long)) {
      for (feedWidth in 200..520) {
        val f = fixture(text, feedWidth)
        val area = f.bubble.components.filterIsInstance<JTextArea>().single()
        val last = area.modelToView2D(text.length)
        assertTrue(area.height >= last.y + last.height - 0.5,
                   "feed $feedWidth: the text area is ${area.height} tall, the text needs ${last.y + last.height}")
      }
    }
  }

  @Test
  fun `the row is as tall as the bubble and its own padding, and never stretches`() {
    val f = fixture(long, feedWidth = 500)
    val insets = f.row.insets
    assertEquals(f.bubble.height + insets.top + insets.bottom, f.row.preferredSize.height)
    assertEquals(f.row.preferredSize.height, f.row.maximumSize.height)
  }

  @Test
  fun `in a wide feed the time is in the corner and the caption is hidden`() {
    val f = fixture("hello", feedWidth = 500)
    val time = f.bubble.components.filterIsInstance<javax.swing.JLabel>().single { it.text == "12:34" }
    val caption = f.bubble.components.filterIsInstance<javax.swing.JLabel>().single { it.text.startsWith("You") }
    assertTrue(time.isVisible)
    assertFalse(caption.isVisible)
    val layout = f.metrics.layout
    assertEquals(f.bubble.width - layout.insetRight, time.x + time.width, "the time sits at the bubble's right padding")
    val text = f.bubble.components.filterIsInstance<JTextArea>().single()
    assertTrue(time.y >= text.y + text.height, "the time is under the text")
  }

  @Test
  fun `in a tight feed the bubble is a plate across the row with the caption above the text`() {
    val f = fixture("hello", feedWidth = 280)
    val available = f.row.width - f.row.insets.left - f.row.insets.right
    assertEquals(available, f.bubble.width)
    val labels = f.bubble.components.filterIsInstance<javax.swing.JLabel>()
    val caption = labels.single { it.text.startsWith("You") }
    val time = labels.single { it.text == "12:34" }
    assertTrue(caption.isVisible)
    assertFalse(time.isVisible)
    val text = f.bubble.components.filterIsInstance<JTextArea>().single()
    assertTrue(caption.y + caption.height <= text.y, "the caption is above the text")
    assertEquals(f.metrics.layout.insetLeft, text.x, "the text starts after the bar and its padding")
  }

  @Test
  fun `the chips sit over the bubble's top right corner and stick out of it`() {
    val strip = MessageChipStrip(
      pinned = false,
      actions = MessageChip.entries.associateWith { MessageChipAction("tip") {} },
    )
    try {
      val f = fixture("hello", feedWidth = 500, chips = strip)
      val offset = JBUI.scale(ChatLayoutSpec.CHIP_OFFSET)
      assertEquals(f.bubble.x + f.bubble.width + offset, strip.x + strip.width)
      assertEquals(f.bubble.y - offset, strip.y)
      // The top inset of the row is exactly the room the chips need above the bubble
      assertTrue(strip.y >= 0, "the chips must not leave the row: y=${strip.y}")
    }
    finally {
      strip.removeNotify()
    }
  }

  @Test
  fun `chips that are not shown let every mouse event through to the text`() {
    val strip = MessageChipStrip(pinned = false, actions = MessageChip.entries.associateWith { MessageChipAction("tip") {} })
    try {
      fixture("hello", feedWidth = 500, chips = strip)
      for (x in 0 until strip.width step 4) assertFalse(strip.contains(x, strip.height / 2), "x=$x")
      strip.setHovered(true)
      assertTrue(strip.contains(strip.width / 2, strip.height / 2))
    }
    finally {
      strip.removeNotify()
    }
  }

  @Test
  fun `a pinned message keeps only its pin in the way of the mouse`() {
    val strip = MessageChipStrip(pinned = true, actions = MessageChip.entries.associateWith { MessageChipAction("tip") {} })
    try {
      fixture("hello", feedWidth = 500, chips = strip)
      val size = JBUI.scale(ChatLayoutSpec.CHIP_SIZE)
      val gap = JBUI.scale(ChatLayoutSpec.CHIP_GAP)
      val centre = size / 2
      assertTrue(strip.contains(centre, centre), "the pin is the first chip")
      assertFalse(strip.contains(size + gap + centre, centre), "the second chip is hidden")
      assertFalse(strip.contains(2 * (size + gap) + centre, centre), "the third chip is hidden")
    }
    finally {
      strip.removeNotify()
    }
  }
}
