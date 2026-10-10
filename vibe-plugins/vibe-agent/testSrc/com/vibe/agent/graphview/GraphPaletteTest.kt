// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graphview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphPaletteTest {
  @Test
  fun `the first six groups take the theme colours, the rest are variants of them`() {
    for (i in 0 until GraphPalette.BASE_COLORS) assertEquals(GraphPalette.base(i).rgb, GraphPalette.colorOf(i).rgb)
    val variants = (GraphPalette.BASE_COLORS until GROUPS).map { GraphPalette.variant(it).rgb }
    assertEquals(variants.size, variants.toSet().size, "two groups of a project got one colour")
    val base = (0 until GraphPalette.BASE_COLORS).map { GraphPalette.base(it).rgb }.toSet()
    assertTrue(variants.none { it in base }, "a variant repeats a theme colour")
  }

  @Test
  fun `a variant is the same on every call`() {
    assertEquals(GraphPalette.variant(17).rgb, GraphPalette.variant(17).rgb)
    assertEquals(GraphPalette.colorOf(17).rgb, GraphPalette.variant(17).rgb)
  }

  private companion object {
    /** More subsystems than a large project has: the VibeIDE repository gives 26 */
    const val GROUPS = 60
  }
}
