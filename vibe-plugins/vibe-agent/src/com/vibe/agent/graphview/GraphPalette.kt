// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graphview

import com.intellij.ui.JBColor
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap

/**
 * Colours of the groups a graph is drawn in: the six theme tokens first, then their hue-rotated variants
 *
 * A project has dozens of subsystems, more than any fixed palette of theme tokens
 * The tokens stay the source of every colour, so a theme repaints the picture and a user theme can override them:
 * A subsystem past the sixth takes the colour of its place in the cycle, turned around the hue wheel
 * And alternately made darker and lighter
 *
 * The token names keep the `Vibe.Docs` prefix: user themes already override them, and a rename would reset that
 */
object GraphPalette {
  const val BASE_COLORS = 6

  /** Defaults are picked to be told apart on a light and a dark theme; the order is fixed */
  private val BASE: List<JBColor> = listOf(
    JBColor.namedColor("Vibe.Docs.category1", JBColor(0x9C6ADE, 0xA47BE8)),
    JBColor.namedColor("Vibe.Docs.category2", JBColor(0x3574F0, 0x548AF7)),
    JBColor.namedColor("Vibe.Docs.category3", JBColor(0x1F9C6B, 0x4CB782)),
    JBColor.namedColor("Vibe.Docs.category4", JBColor(0xC27D04, 0xD6AE58)),
    JBColor.namedColor("Vibe.Docs.category5", JBColor(0x1E9AA8, 0x3FB6C4)),
    JBColor.namedColor("Vibe.Docs.category6", JBColor(0xC0468A, 0xD673A8)),
  )

  /** How far each lap around the palette turns the hue: a golden fraction, so laps never line up with each other */
  private const val HUE_PER_LAP = 0.382f
  private const val DARKER = 0.78f
  private const val LIGHTER = 1.18f

  /** Variants are lazy colours: they read the theme token on every use, so a theme switch repaints them as well */
  private val variants = ConcurrentHashMap<Int, JBColor>()

  /** One of the six theme colours */
  fun base(index: Int): JBColor = BASE[Math.floorMod(index, BASE_COLORS)]

  /** Colour of the n-th group: the theme's own for the first six, a variant of them after that */
  fun colorOf(index: Int): Color {
    if (index in 0 until BASE_COLORS) return BASE[index]
    return variants.getOrPut(index) { JBColor.lazy { variant(index) } }
  }

  /** Pure arithmetic over a base colour, apart from the theme: [colorOf] only chooses which base to feed it */
  internal fun variant(index: Int): Color {
    val lap = index / BASE_COLORS
    val base = BASE[index % BASE_COLORS]
    val hsb = Color.RGBtoHSB(base.red, base.green, base.blue, null)
    val hue = (hsb[0] + lap * HUE_PER_LAP) % 1f
    val brightness = (hsb[2] * if (lap % 2 == 1) DARKER else LIGHTER).coerceIn(0f, 1f)
    return Color.getHSBColor(hue, hsb[1], brightness)
  }
}
