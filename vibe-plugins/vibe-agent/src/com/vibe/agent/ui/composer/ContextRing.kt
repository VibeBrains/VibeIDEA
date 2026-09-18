// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * Заполненность контекста — кольцом в ряду композера, как у VibeIDE.
 *
 * Первая попытка была подписью в десять пикселей у правого края, между часами истории и бегунком
 * занятости: «токены очень мелкие, отображается не так и не на том месте» (владелец, 0.6.5). Число
 * в такой строчке не читается на ходу и требует остановиться и вчитаться — а вопрос «сколько
 * осталось» задают краем глаза.
 *
 * Кольцо отвечает на него формой: пустое — места много, закрашенное — мало. Точное число живёт во
 * всплывашке по клику, там же, где разбивка «чем занято»: цифра нужна, когда уже решил разбираться.
 *
 * Неизвестная доля (провайдер не объявил окно модели) рисуется пустым тусклым кольцом, а не нулём:
 * «не знаем» и «пусто» — разные вещи, и нарисованный ноль обещал бы запас, которого никто не мерил.
 */
class ContextRing(private val onClick: () -> Unit) : JComponent() {
  private var percent: Int? = null
  private var warn = false

  init {
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    isOpaque = false
    addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) = onClick()
    })
  }

  /** Доля занятого окна в процентах, или null — когда её не из чего посчитать. */
  fun setUsage(percent: Int?, tooltip: String?, warn: Boolean) {
    this.percent = percent?.coerceIn(0, 100)
    this.warn = warn
    toolTipText = tooltip
    repaint()
  }

  override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(SIZE), JBUI.scale(SIZE))

  override fun getMinimumSize(): Dimension = preferredSize

  override fun getMaximumSize(): Dimension = preferredSize

  override fun paintComponent(g: Graphics) {
    val g2 = g.create() as Graphics2D
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      val stroke = JBUI.scale(STROKE).toFloat()
      val pad = stroke / 2 + JBUI.scale(PAD)
      val size = (minOf(width, height) - pad * 2).toInt()
      val x = (width - size) / 2
      val y = (height - size) / 2
      g2.stroke = java.awt.BasicStroke(stroke)
      g2.color = TRACK
      g2.drawOval(x, y, size, size)
      val filled = percent ?: return
      if (filled <= 0) return
      g2.color = if (warn) WARN else FILL
      // Против часовой от двенадцати: так растёт любой индикатор заполнения, и читается он без
      // подписи именно поэтому.
      g2.drawArc(x, y, size, size, 90, -(filled * 360 / 100))
    }
    finally {
      g2.dispose()
    }
  }

  private companion object {
    const val SIZE = 16
    const val STROKE = 2
    const val PAD = 1
    val TRACK: JBColor = JBColor.namedColor("Vibe.Composer.contextRingTrack", JBColor(0xC9CCD6, 0x3A4050))
    val FILL: JBColor = JBColor.namedColor("Vibe.Composer.contextRingFill",
                                           JBColor.namedColor("Vibe.Composer.accent", JBColor(0x3574F0, 0x00E5FF)))
    val WARN: JBColor = JBColor.namedColor("Vibe.Composer.contextRingWarn",
                                           JBColor.namedColor("Vibe.Composer.usageWarnForeground", JBColor(0xB8860B, 0xE0A030)))
  }
}
