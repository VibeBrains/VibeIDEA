// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.settings

import java.awt.Component
import javax.swing.BoxLayout
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A hint is drawn at the bounds its layout gives it
 *
 * A hint wraps its text to the width it is given, and that happens inside the layout pass that gives the width
 * A hint that came out of that pass with no size paints nothing, and the page shows a gap where the text should be:
 * the body of a card is built once and laid out once, so there is no second pass to repair it
 */
class WrappingHintTest {
  private val text = "A sentence long enough to need several lines at the width of a narrow settings page, so that wrapping it matters"

  private fun hintIn(column: JPanel): WrappingHint {
    val hint = SettingsUi.hint(text) as WrappingHint
    hint.alignmentX = Component.LEFT_ALIGNMENT
    column.add(hint)
    return hint
  }

  private fun column(): JPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

  @Test
  fun `one layout pass leaves the hint at the size it was given`() {
    val column = column()
    val hint = hintIn(column)
    column.setSize(WIDTH, HEIGHT)
    column.doLayout()
    assertEquals(WIDTH, hint.width, "the hint came out of its first layout pass with no width")
    assertTrue(hint.height > 0, "the hint came out of its first layout pass with no height")
  }

  @Test
  fun `a hint wrapped again at another width keeps the bounds of that layout too`() {
    val column = column()
    val hint = hintIn(column)
    column.setSize(WIDTH, HEIGHT)
    column.doLayout()
    column.setSize(NARROW, HEIGHT)
    column.doLayout()
    assertEquals(NARROW, hint.width)
    assertTrue(hint.height > 0)
  }

  @Test
  fun `a narrower hint asks for more height`() {
    val column = column()
    val hint = hintIn(column)
    column.setSize(WIDTH, HEIGHT)
    column.doLayout()
    // The second pass, which a page gets from the layout it is in: the height is asked for again at the width the hint has now
    column.invalidate()
    column.doLayout()
    val wide = hint.preferredSize.height
    column.setSize(NARROW, HEIGHT)
    column.doLayout()
    column.invalidate()
    column.doLayout()
    assertTrue(hint.preferredSize.height > wide, "wrapped to $NARROW points the text must be taller than at $WIDTH, was $wide")
  }

  private companion object {
    const val WIDTH = 600
    const val NARROW = 150
    const val HEIGHT = 400
  }
}
