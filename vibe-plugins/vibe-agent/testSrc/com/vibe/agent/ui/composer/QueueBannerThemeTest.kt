// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui.composer

import java.awt.Color
import java.awt.image.BufferedImage
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The queue strip takes its frame from the theme key `Vibe.Composer.queueBorder`
 *
 * The platform reads a theme key that ends with `Border` as a border object, not a colour, so a hex literal under it never
 * reaches the code. The themes now name a palette entry there (the gate `checkVibeUi.sh` holds that for all of them)
 * and the platform substitutes the palette colour before the key is parsed. This test covers the other half:
 * whatever colour the theme puts under the key, the strip draws its frame with it and not with the built-in fallback
 */
class QueueBannerThemeTest {
  private val key = "Vibe.Composer.queueBorder"

  private fun frameColorOfTopEdge(banner: QueueBanner): Int {
    val width = 200
    val height = 60
    banner.setSize(width, height)
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
      banner.paint(g)
    }
    finally {
      g.dispose()
    }
    // The middle of the top edge: clear of the rounded corners and of the content, which starts below the padding
    return image.getRGB(width / 2, 0)
  }

  private fun bannerWithOneNote(): QueueBanner {
    val queue = InjectionQueue()
    queue.add(ComposedMessage("a note while the agent works"))
    return QueueBanner(queue)
  }

  @Test
  fun `the frame of the strip is the colour the theme gives under the queueBorder key`() {
    val themed = Color(0x12, 0xAB, 0x34)
    UIManager.put(key, themed)
    try {
      assertEquals(themed.rgb, frameColorOfTopEdge(bannerWithOneNote()))
    }
    finally {
      UIManager.put(key, null)
    }
  }

  @Test
  fun `another theme colour gives another frame`() {
    val other = Color(0xE0, 0x30, 0x90)
    UIManager.put(key, other)
    try {
      assertEquals(other.rgb, frameColorOfTopEdge(bannerWithOneNote()))
    }
    finally {
      UIManager.put(key, null)
    }
  }
}
