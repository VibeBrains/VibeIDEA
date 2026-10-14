// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import com.intellij.ui.ColorHexUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * How the platform reads the translucent colours of our themes
 *
 * The bubble, its border and the reply's rule are written in the theme files as `#RRGGBBAA`
 * The theme loader reads a palette value with [ColorHexUtil] (`parseColorOrNull`), which takes eight digits with the alpha LAST
 * The opposite of `0xAARRGGBB`, which is what Java's own `Color(int, true)` expects
 * A theme author who guesses the order gets a nearly invisible bubble in the wrong colour
 * So the order is pinned here against the platform's parser
 */
class ThemeAlphaColorTest {
  @Test
  fun `eight digits are red, green, blue and alpha, in that order`() {
    val color = assertNotNull(ColorHexUtil.fromHexOrNull("#00E5FF26"))
    assertEquals(0x00, color.red)
    assertEquals(0xE5, color.green)
    assertEquals(0xFF, color.blue)
    assertEquals(0x26, color.alpha)
  }

  @Test
  fun `the three opacities of the chat tokens are 15, 55 and 25 percent`() {
    // 0x26 / 255, 0x8c / 255 and 0x40 / 255, the shares VibeIDE uses for the bubble, its border and the reply's rule
    assertEquals(0.15, assertNotNull(ColorHexUtil.fromHexOrNull("#7aa2f726")).alpha / 255.0, 0.005)
    assertEquals(0.55, assertNotNull(ColorHexUtil.fromHexOrNull("#7aa2f78c")).alpha / 255.0, 0.005)
    assertEquals(0.25, assertNotNull(ColorHexUtil.fromHexOrNull("#c4cbe340")).alpha / 255.0, 0.005)
  }

  @Test
  fun `six digits are opaque`() {
    assertEquals(255, assertNotNull(ColorHexUtil.fromHexOrNull("#7aa2f7")).alpha)
  }
}
