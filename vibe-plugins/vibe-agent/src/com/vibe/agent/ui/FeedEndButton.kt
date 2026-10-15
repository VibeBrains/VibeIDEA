// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * Лента с круглой кнопкой «в конец» поверх неё.
 *
 * Зачем она есть: лента больше не таскает себя вниз сама. Раньше вниз её тянули две силы —
 * следование за потоком и каретка компонента, дёргавшая `scrollRectToVisible` на каждую вставку
 * текста; из-за второй чат прыгал в конец, даже когда человек ушёл читать наверх (жалоба брата
 * владельца, 18.09.2026). Каретка молчит ([FeedSelection.QuietCaret]), следование заперто на время
 * выделения, — и человеку нужен способ вернуться вниз одним движением. Это он и есть.
 *
 * Правило показа ровно одно и то же у нас и у VibeIDE: кнопка видна тогда и только тогда, когда
 * лента НЕ внизу. Ни поток, ни ход агента на это не влияют — иначе кнопка пропадает ровно в тот
 * момент, когда она нужнее всего, за чем VibeIDE уже сходил (vscode#326952).
 *
 * Слоями, а не BorderLayout: кнопка лежит НАД лентой и не отъедает у неё ширину.
 */
class FeedEndButton(private val scroll: JScrollPane) : JLayeredPane() {
  private val button = RoundIconButton().apply {
    toolTipText = t("chat.feed.toEnd")
    isVisible = false
  }

  init {
    add(scroll, DEFAULT_LAYER)
    add(button, PALETTE_LAYER)
    button.onClick = { scrollToEnd() }
    scroll.verticalScrollBar.addAdjustmentListener { update() }
    scroll.viewport.addChangeListener {
      if (endPending) scroll.verticalScrollBar.let { it.value = it.maximum }
      update()
    }
    // A wheel or a drag is the person taking over: the pending pin must not pull them back down
    scroll.addMouseWheelListener { endPending = false }
    scroll.addHierarchyListener { e ->
      val shown = e.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && scroll.isShowing
      if (shown && shownAgain) {
        shownAgain = false
        scrollToEnd()
        releasePinLater()
      }
    }
    scroll.verticalScrollBar.addMouseListener(object : java.awt.event.MouseAdapter() {
      override fun mousePressed(e: java.awt.event.MouseEvent) { endPending = false }
    })
  }

  /** True while an opened thread is still being laid out and must end up at its bottom */
  private var endPending = false

  /**
   * Opens the feed at its end, the way a chat is read
   * A restored thread is laid out late: the panel may be hidden at start and its height arrives in several passes,
   * so one scroll lands at the top of a list that keeps growing. The pin follows the layout for a short while
   */
  fun pinToEnd() {
    endPending = true
    scrollToEnd()
    // A hidden panel has no height yet: the short pin starts only once the feed is on screen
    if (scroll.isShowing) releasePinLater() else shownAgain = true
  }

  private var shownAgain = false

  private fun releasePinLater() {
    javax.swing.Timer(PIN_MS) { endPending = false }.apply { isRepeats = false; start() }
  }

  /** Вниз до упора; `maximum` меняется по мере досчёта раскладки, поэтому шаг повторяется. */
  fun scrollToEnd() {
    val bar = scroll.verticalScrollBar
    bar.value = bar.maximum
    SwingUtilities.invokeLater { bar.value = bar.maximum }
  }

  /** Внизу ли лента — с тем же допуском, с каким лента решает следовать за потоком. */
  fun isAtEnd(): Boolean {
    val bar = scroll.verticalScrollBar
    return bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(SLACK)
  }

  fun update() {
    val show = !isAtEnd()
    if (button.isVisible != show) {
      button.isVisible = show
      button.repaint()
    }
  }

  override fun doLayout() {
    scroll.setBounds(0, 0, width, height)
    val size = JBUI.scale(SIZE)
    val margin = JBUI.scale(MARGIN)
    // Справа над полосой прокрутки и над нижним краем: там, где её ищут по привычке из браузера.
    button.setBounds(width - size - margin, height - size - margin, size, size)
  }

  override fun getPreferredSize(): Dimension = scroll.preferredSize

  override fun getMinimumSize(): Dimension = scroll.minimumSize

  /** Круглая кнопка со стрелкой: фон — токен темы, стрелка — иконка платформы. */
  private class RoundIconButton : JComponent() {
    var onClick: () -> Unit = {}
    private var hovered = false

    init {
      cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
      addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) = onClick()
        override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
        override fun mouseExited(e: MouseEvent) { hovered = false; repaint() }
      })
    }

    override fun paintComponent(g: Graphics) {
      val g2 = g.create() as Graphics2D
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = if (hovered) HOVER_BG else BG
        g2.fillOval(0, 0, width - 1, height - 1)
        g2.color = BORDER
        g2.drawOval(0, 0, width - 1, height - 1)
        val icon = AllIcons.General.ArrowDown
        icon.paintIcon(this, g2, (width - icon.iconWidth) / 2, (height - icon.iconHeight) / 2)
      }
      finally {
        g2.dispose()
      }
    }

    private companion object {
      val BG: JBColor = JBColor.namedColor("Vibe.Feed.endButtonBackground", JBColor(0xE8E8EC, 0x2B2D30))
      val HOVER_BG: JBColor = JBColor.namedColor("Vibe.Feed.endButtonHoverBackground", JBColor(0xDCDCE2, 0x393B40))
      val BORDER: JBColor = JBColor.namedColor("Vibe.Feed.endButtonBorder", JBColor(0xC4C4C8, 0x4A4C50))
    }
  }

  private companion object {
    const val SIZE = 26
    const val MARGIN = 12

    /** Тот же допуск «считается низом», что у следования за потоком: два правила о низе — два низа. */
    const val SLACK = 24

    // How long an opened thread keeps following its own layout down; long enough for a hidden panel shown at start
    const val PIN_MS = 3000
  }
}
