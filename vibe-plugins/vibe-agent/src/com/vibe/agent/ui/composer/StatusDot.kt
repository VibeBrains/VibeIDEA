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
   * Минимум НЕ равен предпочтительному: иначе строка не сжимается никогда.
   *
   * Цена равенства измерена: у имени инструмента длины нет («Инструмент: Выполнить команду —
   * npm install -g @vtsls/language-server»), а несжимаемый компонент в `BorderLayout.EAST`
   * не переносится и не усекается — он НАКЛАДЫВАЕТСЯ на западного соседа, и человек видит кашу
   * из двух строк поверх друг друга (снимок владельца 21.09.2026). Тот же класс дефекта, что
   * трижды обрезал страницы настроек: несжимаемый минимум плюс раскладка, которая при нехватке
   * места не сжимает, а кладёт одно на другое.
   *
   * Минимум — точка и несколько символов: меньше нечего показывать, больше — повод для наложения.
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
      // Усечение, а не выход за край: обрезанный молча текст неотличим от короткого, а многоточие
      // называет, что сказано не всё. Полная фраза остаётся в подсказке — её ставит setState.
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
     * Уместить [text] в [available] пикселей, при нехватке — обрезать и закончить многоточием.
     *
     * Чистая функция на метриках, а не метод компонента: её итог мерится тестом без окна и
     * шрифтов экрана — ровно то, чего не умел прежний гейт, зеленевший на наложенных строках.
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
    /** Сколько символов строка обязана показать даже в самой узкой панели. */
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
