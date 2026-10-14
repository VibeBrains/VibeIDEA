// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.JTextArea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bubble as it is painted: which pixels are filled, which are the border, where the tail is
 *
 * A bubble that looks wrong is wrong in a few corner pixels, and a screenshot is a bad way to count them
 * So the bubble is painted into an image here and the pixels the layout promises are read back
 */
class UserBubblePaintTest {
  private fun painted(feedWidth: Int, text: String = "hello\nsecond line"): BufferedImage {
    val metrics = ChatFeedMetrics()
    metrics.update(feedWidth)
    val area = JTextArea().apply {
      isEditable = false
      isOpaque = false
      lineWrap = true
      wrapStyleWord = true
      font = JBFont.label().deriveFont(13f)
      border = JBUI.Borders.empty()
      this.text = text
    }
    val bubble = UserBubble(area, "12:34", "You · 12:34", dateTooltip = null, metrics = metrics)
    val size: Dimension = bubble.sizeFor(metrics.rowWidth() - 8)
    bubble.setSize(size)
    bubble.doLayout()
    val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      bubble.paint(g)
    }
    finally {
      g.dispose()
    }
    return image
  }

  private fun alpha(image: BufferedImage, x: Int, y: Int) = (image.getRGB(x, y) ushr 24) and 0xFF

  @Test
  fun `the three big corners are cut away and the tail corner is not`() {
    val image = painted(500)
    val w = image.width
    val h = image.height
    assertEquals(0, alpha(image, 0, 0), "top left")
    assertEquals(0, alpha(image, w - 1, 0), "top right")
    assertEquals(0, alpha(image, 0, h - 1), "bottom left")
    assertTrue(alpha(image, w - 2, h - 2) > 0, "the tail corner is almost square, so its pixel is painted")
  }

  @Test
  fun `the outline is stronger than the fill and runs along every edge`() {
    val image = painted(500)
    val w = image.width
    val h = image.height
    val fill = alpha(image, w / 2, 3)
    assertTrue(fill > 0, "the fill is painted")
    assertTrue(alpha(image, w / 2, 0) > fill, "top edge: the outline over the fill")
    assertTrue(alpha(image, 0, h / 2) > fill, "left edge")
    assertTrue(alpha(image, w - 1, h / 2) > fill, "right edge")
    assertTrue(alpha(image, w / 2, h - 1) > fill, "bottom edge")
  }

  @Test
  fun `the plate has a bar on its left that is three columns wide and stronger than the fill`() {
    val image = painted(280)
    val h = image.height
    val fill = alpha(image, image.width / 2, 3)
    val bar = alpha(image, 1, h / 2)
    assertTrue(bar > fill, "bar $bar, fill $fill")
    for (x in 0 until 3) assertEquals(bar, alpha(image, x, h / 2), "bar column $x")
    assertEquals(fill, alpha(image, 4, h / 2), "right after the bar the fill starts, with no outline")
  }

  @Test
  fun `the plate has no outline on the other three sides`() {
    val image = painted(280)
    val w = image.width
    val h = image.height
    val fill = alpha(image, w / 2, 3)
    assertEquals(fill, alpha(image, w / 2, 0), "top edge")
    assertEquals(fill, alpha(image, w - 1, h / 2), "right edge")
    assertEquals(fill, alpha(image, w / 2, h - 1), "bottom edge")
  }

  @Test
  fun `the plate's corner is cut by its own radius`() {
    val image = painted(280)
    assertEquals(0, alpha(image, 0, 0))
    assertEquals(0, alpha(image, image.width - 1, 0))
  }
}
