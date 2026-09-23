// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.Timer

/**
 * Что панель делает прямо сейчас — словом и цветной точкой, как у VibeIDE.
 *
 * Чем это лучше крутилки: крутилка говорит «что-то идёт» и молчит о том, ЧТО именно, — а разница
 * между «модель думает», «выполняю инструмент» и «жду провайдера» это разница между «подожди»,
 * «посмотри, что оно делает» и «проверь ключ». Точка при этом несёт состояние цветом и потому
 * читается боковым зрением, не требуя прочесть слово (просьба владельца, 18.09.2026).
 *
 * Анимация — дыхание точки, а не вращение: вращающийся элемент притягивает взгляд к себе и не
 * отпускает, а этот индикатор человек видит десятки минут подряд. Пока работы нет, таймер
 * остановлен: незаметная анимация в простое всё равно будит отрисовку.
 */
class StatusDot : JComponent() {
  enum class State { READY, THINKING, TOOL, WAITING, ERROR }

  private var state = State.READY
  private var detail: String? = null
  private var phase = 0.0

  private val pulse = Timer(PULSE_MS) {
    phase += PULSE_STEP
    if (phase > 1.0) phase -= 1.0
    repaint()
  }.apply { isRepeats = true }

  init {
    isOpaque = false
    font = JBFont.label().deriveFont(Font.PLAIN, 11f)
  }

  /** [detail] — короткое уточнение к состоянию (имя инструмента), или null. */
  fun setState(state: State, detail: String? = null) {
    this.state = state
    this.detail = detail
    toolTipText = text()
    val animate = state == State.THINKING || state == State.TOOL || state == State.WAITING
    if (animate && !pulse.isRunning) pulse.start()
    if (!animate && pulse.isRunning) { pulse.stop(); phase = 0.0 }
    revalidate()
    repaint()
  }

  /** Освободить таймер: живой Timer держит панель после закрытия вкладки. */
  fun dispose() = pulse.stop()

  private fun text(): String {
    val base = when (state) {
      State.READY -> t("status.ready")
      State.THINKING -> t("status.thinking")
      State.TOOL -> t("status.tool")
      State.WAITING -> t("status.waiting")
      State.ERROR -> t("status.error")
    }
    return detail?.takeIf { it.isNotBlank() }?.let { "$base: $it" } ?: base
  }

  private fun color(): JBColor = when (state) {
    State.READY -> READY_COLOR
    State.THINKING -> THINKING_COLOR
    State.TOOL -> TOOL_COLOR
    State.WAITING -> WAITING_COLOR
    State.ERROR -> ERROR_COLOR
  }

  override fun getPreferredSize(): Dimension {
    val metrics = getFontMetrics(font)
    return Dimension(metrics.stringWidth(text()) + JBUI.scale(DOT + GAP * 2), metrics.height)
  }

  /**
   * The minimum is NOT the preferred size: otherwise the line can never shrink.
   *
   * A tool name has no length limit, and a component that cannot shrink inside `BorderLayout.EAST` is neither wrapped
   * nor truncated — it is drawn OVER its western neighbour, two lines on top of each other. It is the same kind of
   * defect as a settings page cut off at the right edge: an unshrinkable minimum plus a layout that overlaps instead
   * of shrinking when space runs out.
   *
   * The minimum is the dot and a few characters: less shows nothing, more invites overlapping.
   */
  override fun getMinimumSize(): Dimension {
    val metrics = getFontMetrics(font)
    return Dimension(metrics.charWidth('m') * MIN_CHARS + JBUI.scale(DOT + GAP * 2), metrics.height)
  }

  override fun getMaximumSize(): Dimension = preferredSize

  override fun paintComponent(g: Graphics) {
    val g2 = g.create() as Graphics2D
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
      g2.font = font
      val metrics = g2.fontMetrics
      // Truncate rather than overflow: silently clipped text looks like short text, while an ellipsis says something
      // was left out. The full phrase stays in the tooltip, which setState sets.
      val label = fit(text(), metrics, width - JBUI.scale(DOT + GAP * 2))
      g2.color = LABEL
      g2.drawString(label, 0, metrics.ascent)
      val dot = JBUI.scale(DOT)
      val x = metrics.stringWidth(label) + JBUI.scale(GAP)
      val y = (metrics.height - dot) / 2
      // Дыхание: радиус тот же, меняется прозрачность — размер, скачущий в ряду пилюль, дёргает
      // соседей, а цвет не двигает ничего.
      val alpha = if (pulse.isRunning) (MIN_ALPHA + (1 - MIN_ALPHA) * kotlin.math.abs(kotlin.math.sin(phase * Math.PI))) else 1.0
      val base = color()
      g2.color = java.awt.Color(base.red, base.green, base.blue, (alpha * 255).toInt().coerceIn(0, 255))
      g2.fillOval(x, y, dot, dot)
    }
    finally {
      g2.dispose()
    }
  }

  internal companion object {
    /**
     * Fit [text] into [available] pixels; when it does not fit, cut it and end with an ellipsis.
     *
     * A pure function over font metrics rather than a component method, so its result can be measured by a test
     * without a window or screen fonts.
     */
    fun fit(text: String, metrics: java.awt.FontMetrics, available: Int): String {
      if (available <= 0) return ""
      if (metrics.stringWidth(text) <= available) return text
      val ellipsisWidth = metrics.stringWidth(ELLIPSIS)
      if (ellipsisWidth > available) return ""
      var end = text.length
      while (end > 0 && metrics.stringWidth(text.substring(0, end)) + ellipsisWidth > available) end--
      return text.substring(0, end) + ELLIPSIS
    }

    const val DOT = 7
    const val GAP = 5
    /** How many characters the line must show even in the narrowest panel. */
    const val MIN_CHARS = 3
    const val ELLIPSIS = "…"
    const val PULSE_MS = 60
    const val PULSE_STEP = 0.04
    const val MIN_ALPHA = 0.35

    val LABEL: JBColor = JBColor.namedColor("Vibe.Composer.usageForeground", JBColor.GRAY)
    val READY_COLOR: JBColor = JBColor.namedColor("Vibe.Status.ready", JBColor(0x4CB782, 0x5FAD5F))
    val THINKING_COLOR: JBColor = JBColor.namedColor("Vibe.Status.thinking", JBColor(0x3574F0, 0x00E5FF))
    val TOOL_COLOR: JBColor = JBColor.namedColor("Vibe.Status.tool", JBColor(0x9D5CFF, 0x9D5CFF))
    val WAITING_COLOR: JBColor = JBColor.namedColor("Vibe.Status.waiting", JBColor(0xB8860B, 0xE0A030))
    val ERROR_COLOR: JBColor = JBColor.namedColor("Vibe.Status.error", JBColor(0xE05252, 0xFF6B5A))
  }
}
