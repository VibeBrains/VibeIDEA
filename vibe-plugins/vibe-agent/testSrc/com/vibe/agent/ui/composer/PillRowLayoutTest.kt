// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui.composer

import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pill row gives width away from the model pill before it wraps, and wraps rather than hides
 * Measured on the bounds the layout hands out: the outcome, not a component property
 */
class PillRowLayoutTest {
  private class Pill(w: Int, h: Int = 24) : JComponent() {
    init { preferredSize = Dimension(w, h) }
  }

  private val gap = 4
  private val mode = Pill(80)
  private val model = Pill(260)
  private val pipeline = Pill(24)
  private val permission = Pill(100)
  private val ring = Pill(20, 20)

  /** On the first line: the ring is lower than the pills and centred in the line, so its y is not the pills' y */
  private fun onFirstLine(c: JComponent) = c.y < mode.y + mode.height

  private fun row(width: Int): JPanel {
    val layout = PillRowLayout(gap, 72).also { it.elastic = model }
    return JPanel(layout).apply {
      listOf(mode, model, pipeline, permission, ring).forEach { add(it) }
      setSize(width, 200)
      doLayout()
    }
  }

  @Test
  fun `a long model name shrinks instead of pushing the ring to a second line`() {
    // Everything wants 80+260+24+100+20 and four gaps = 500; the row has 420
    row(420)
    assertTrue(onFirstLine(ring), "кольцо контекста ушло на вторую строку")
    assertEquals(180, model.width, "модель уступила ровно недостающее")
    assertTrue(ring.x + ring.width <= 420, "кольцо за краем ряда")
  }

  @Test
  fun `with room enough nothing shrinks`() {
    row(600)
    assertEquals(260, model.width)
    assertTrue(onFirstLine(ring))
  }

  @Test
  fun `when even the shrunk model does not fit, the row wraps and hides nothing`() {
    // The model at its minimum of 72 still leaves 312 wanted against 250 available
    val panel = row(250)
    assertEquals(72, model.width)
    assertTrue(!onFirstLine(ring), "ряд не перенёсся")
    panel.components.forEach { assertTrue(it.x + it.width <= 250, "пилюля за краем ряда") }
    assertTrue(panel.preferredSize.height > 24, "высота ряда не учла вторую строку")
  }

  @Test
  fun `a hidden pill takes no room`() {
    pipeline.isVisible = false
    row(420)
    assertTrue(onFirstLine(ring))
    assertEquals(208, model.width)
  }
}
