// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.image.BufferedImage
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The status line must neither overlap its neighbours nor run past the edge.
 *
 * With `StatusDot.minimumSize` equal to its preferred size — which grows with the text — the component cannot shrink,
 * and `BorderLayout` does not shrink or wrap when width runs out: it draws `EAST` over `WEST`. A long tool name then
 * lands on top of the composer header.
 *
 * The lesson from the settings pages applies: **measure the outcome, not a component property**. So what is measured
 * below is the string that will actually be painted.
 *
 * What is and is NOT measured here, stated plainly so the test does not promise more than it does. The real guard
 * against overlapping is the shrinkable minimum plus truncation: together they rule it out for ANY layout, and the
 * "shrinks" and "truncates" checks catch a regression (restoring the old minimum makes the first one fail). The
 * two-line check describes the composer layout but builds it itself: `ComposerPanel` needs a `Project`, and a
 * platform fixture for one line position costs more than it gives. If the layout ever folds back into one row, that
 * check will not notice — the shrink check will, and that is enough.
 */
class StatusStripLayoutTest {
  /** Font metrics without a screen: the test must run headless like the others. */
  private fun metrics() = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
    .also { it.font = JLabel().font }
    .fontMetrics

  private val longText = "Инструмент: Выполнить команду — npm install -g @vtsls/language-server"

  /**
   * A state with a live timer: `TOOL` pulses, and a timer left running outlives the test (the platform fails it in
   * `afterEach`). So every check releases the timer itself.
   */
  private fun withToolState(body: (StatusDot) -> Unit) {
    val dot = StatusDot()
    dot.setState(StatusDot.State.TOOL, "Выполнить команду — npm install -g @vtsls/language-server")
    try {
      body(dot)
    }
    finally {
      dot.dispose()
    }
  }

  @Test
  fun `длинное состояние сжимается, а не задаёт пол ширины`() = withToolState { dot ->
    val preferred = dot.preferredSize.width
    val minimum = dot.minimumSize.width
    assertTrue(minimum < preferred,
               "состояние просит минимум $minimum при предпочтительной $preferred — оно не сжимается")
  }

  @Test
  fun `в узкой панели строка состояния не накладывается на полоску действий`() = withToolState { dot ->
    val status = JPanel(BorderLayout()).apply { add(dot, BorderLayout.CENTER) }
    val actions = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
      add(JLabel("Нет файлов с изменениями"))
      add(JLabel("[]"))
      add(JLabel("[]"))
    }
    // Two LINES, as in the composer: the status above the action strip.
    val column = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      add(status)
      add(actions)
    }
    column.size = Dimension(NARROW, column.preferredSize.height)
    column.doLayout()
    status.doLayout()
    actions.doLayout()

    val statusBounds = status.bounds
    val actionsBounds = actions.bounds
    assertFalse(statusBounds.intersects(actionsBounds),
                "строка состояния $statusBounds пересекает полоску действий $actionsBounds")
    assertTrue(dot.width <= NARROW,
               "состояние шириной ${dot.width} вылезает за панель шириной $NARROW")
  }

  @Test
  fun `не поместившийся текст усекается многоточием, а не обрезается молча`() {
    val metrics = metrics()
    val available = metrics.stringWidth(longText) / 3
    val shown = StatusDot.fit(longText, metrics, available)
    assertTrue(shown.endsWith("…"), "усечённый текст «$shown» не назван многоточием")
    assertTrue(metrics.stringWidth(shown) <= available,
               "усечённый текст шириной ${metrics.stringWidth(shown)} не влез в $available")
    assertTrue(shown.length < longText.length, "текст не усечён вовсе")
  }

  @Test
  fun `помещающийся текст не трогается`() {
    val metrics = metrics()
    val short = "Готово"
    assertEquals(short, StatusDot.fit(short, metrics, metrics.stringWidth(short) + 10),
                 "короткий текст усечён без нужды")
  }

  @Test
  fun `нулевая ширина не роняет отрисовку`() {
    val metrics = metrics()
    assertTrue(StatusDot.fit(longText, metrics, 0).isEmpty(), "при нулевой ширине ждём пустую строку")
    assertTrue(StatusDot.fit(longText, metrics, -5).isEmpty(), "при отрицательной ширине ждём пустую строку")
  }

  private companion object {
    /** A narrow agent panel: at about this width the two strips used to overlap. */
    const val NARROW = 320
  }
}
