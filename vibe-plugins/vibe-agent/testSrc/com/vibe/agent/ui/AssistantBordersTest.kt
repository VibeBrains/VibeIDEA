// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.image.BufferedImage
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The reply's rule and gutter, checked by the pixels they paint and the room they take
 *
 * The rule is drawn by a border so that it follows the reply however tall it grows
 * What matters is that it is where the layout says (the first two columns)
 * The gap after it must stay empty, and a narrow feed must paint no rule at all
 */
class AssistantBordersTest {
  private val rule = Color(200, 40, 40)

  private fun metrics(feedWidth: Int) = ChatFeedMetrics().also { it.update(feedWidth) }

  private fun paint(feedWidth: Int): BufferedImage {
    val border = AssistantRuleBorder(metrics(feedWidth)) { rule }
    val image = BufferedImage(60, 20, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
      border.paintBorder(JPanel(), g, 0, 0, 60, 20)
    }
    finally {
      g.dispose()
    }
    return image
  }

  private fun alphaAt(image: BufferedImage, x: Int) = (image.getRGB(x, 10) ushr 24) and 0xFF

  @Test
  fun `in a wide feed the rule fills its two columns, top to bottom, and the gap stays empty`() {
    val image = paint(500)
    for (x in 0 until 2) {
      assertEquals(rule.rgb, image.getRGB(x, 10), "column $x")
      assertEquals(255, alphaAt(image, x))
    }
    assertEquals(0, alphaAt(image, 2), "the gap after the rule")
    assertEquals(0, alphaAt(image, 11))
    assertEquals(rule.rgb, image.getRGB(0, 0))
    assertEquals(rule.rgb, image.getRGB(0, 19))
  }

  @Test
  fun `in a narrow feed there is no rule at all`() {
    val image = paint(360)
    for (x in 0 until 12) assertEquals(0, alphaAt(image, x), "column $x")
  }

  @Test
  fun `the rule and its gap take 12 pixels on the left of a wide reply and nothing on a narrow one`() {
    assertEquals(12, AssistantRuleBorder(metrics(500)) { rule }.getBorderInsets(null).left)
    assertEquals(0, AssistantRuleBorder(metrics(360)) { rule }.getBorderInsets(null).left)
  }

  @Test
  fun `the row border adds the gutter to the right side only`() {
    val wide = AssistantRowBorder(metrics(500)).getBorderInsets(null)
    val narrow = AssistantRowBorder(metrics(360)).getBorderInsets(null)
    assertEquals(JBUI.scale(ChatLayoutSpec.ROW_PAD_SIDE) + 90, wide.right)
    assertEquals(JBUI.scale(ChatLayoutSpec.ROW_PAD_SIDE), narrow.right)
    assertEquals(JBUI.scale(ChatLayoutSpec.ROW_PAD_SIDE), wide.left)
    assertEquals(JBUI.scale(ChatLayoutSpec.ROW_PAD_TOP), wide.top)
    assertEquals(JBUI.scale(ChatLayoutSpec.ROW_PAD_BOTTOM), wide.bottom)
  }

  @Test
  fun `the insets follow the feed when it is resized, without the border being rebuilt`() {
    val metrics = metrics(500)
    val border = AssistantRowBorder(metrics)
    val before = border.getBorderInsets(null).right
    metrics.update(300)
    assertEquals(JBUI.scale(ChatLayoutSpec.ROW_PAD_SIDE), border.getBorderInsets(null).right)
    metrics.update(700)
    assertEquals(JBUI.scale(ChatLayoutSpec.ROW_PAD_SIDE) + 126, border.getBorderInsets(null).right)
    assertEquals(JBUI.scale(ChatLayoutSpec.ROW_PAD_SIDE) + 90, before)
  }
}
