// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.history

import com.vibe.agent.i18n.VibeI18n.t

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.vibe.agent.history.Role
import com.vibe.agent.history.TranscriptSearch
import com.vibe.agent.ui.composer.PillButton
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagLayout
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * One thread card (VibeIDE history row): title + optional project badge on the first
 * line, optional search-quote line below, and a right-hand area that shows the
 * «N · date» badge normally and swaps it for action icons on hover (same reserved
 * width — the card never jumps). Delete is two-step: the trash icon arms an inline
 * ✕/✓ confirm pair. All colors come from theme tokens.
 */
internal class ThreadRow(
  title: String,
  quote: TranscriptSearch.Quote?,
  badgeText: String,
  projectBadge: String?,
  showMoveHere: Boolean,
  private val isCurrent: Boolean,
  private val onOpen: () -> Unit,
  onOpenQuote: () -> Unit,
  onMoveHere: () -> Unit,
  onDuplicate: () -> Unit,
  onDelete: () -> Unit,
) : JPanel(BorderLayout(JBUI.scale(H_GAP), 0)) {
  private var hover = false
  private var armed = false
  private val cards = JPanel(CardLayout()).apply { isOpaque = false }

  init {
    isOpaque = false
    border = JBUI.Borders.empty(PAD_V, PAD_H)
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

    val titleLabel = JLabel(title.ifEmpty { "\"\"" }).apply { font = JBFont.label() }
    val lineRow = JPanel(BorderLayout(JBUI.scale(LINE_GAP), 0)).apply {
      isOpaque = false
      add(titleLabel, BorderLayout.CENTER)
      projectBadge?.let { badge ->
        // The tooltip registers the pill with ToolTipManager, which swallows mouse events:
        // hover and the open click must be re-attached explicitly.
        add(HistoryPill(badge.uppercase(), maxWidth = PROJECT_BADGE_MAX_WIDTH).apply {
          toolTipText = badge
          trackHover(this)
          addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
              if (e.button == MouseEvent.BUTTON1) onOpen()
            }
          })
        }, BorderLayout.EAST)
      }
    }
    val textColumn = JPanel(BorderLayout(0, JBUI.scale(QUOTE_GAP))).apply {
      isOpaque = false
      add(lineRow, BorderLayout.NORTH)
      quote?.let { add(buildQuoteLine(it, onOpenQuote), BorderLayout.SOUTH) }
    }
    add(textColumn, BorderLayout.CENTER)

    buildRightCards(badgeText, showMoveHere, onMoveHere, onDuplicate, onDelete)
    add(JPanel(GridBagLayout()).apply { isOpaque = false; add(cards) }, BorderLayout.EAST)

    trackHover(this)
    addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) {
        if (e.button == MouseEvent.BUTTON1) onOpen()
      }
    })
    showCard(CARD_BADGE)
  }

  /** The row stretches to the list width but never grows vertically. */
  override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

  private fun buildQuoteLine(quote: TranscriptSearch.Quote, onOpenQuote: () -> Unit): JLabel {
    val prefix = when (quote.role) {
      Role.USER -> t("history.quote.you")
      Role.ASSISTANT -> t("history.quote.answer")
      Role.OTHER -> t("history.quote.other")
    }
    return JLabel("$prefix ${quote.snippet}").apply {
      font = JBFont.label().deriveFont(QUOTE_FONT_SIZE)
      foreground = MUTED
      toolTipText = t("history.quote.open")
      cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
      trackHover(this)
      addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
          if (e.button == MouseEvent.BUTTON1) onOpenQuote()
        }
      })
    }
  }

  private fun buildRightCards(
    badgeText: String,
    showMoveHere: Boolean,
    onMoveHere: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
  ) {
    cards.add(JPanel(GridBagLayout()).apply { isOpaque = false; add(HistoryPill(badgeText)) }, CARD_BADGE)

    val actions = iconRow()
    if (showMoveHere) {
      actions.add(iconButton(AllIcons.Actions.MoveTo2, t("history.action.moveHere"), onMoveHere))
    }
    actions.add(iconButton(AllIcons.Actions.Copy, t("history.action.duplicate"), onDuplicate))
    actions.add(iconButton(AllIcons.Actions.GC, t("history.action.delete")) { armed = true; showCard(CARD_CONFIRM) })
    cards.add(actions, CARD_ACTIONS)

    val confirm = iconRow()
    confirm.add(iconButton(AllIcons.Actions.Cancel, t("common.cancel")) { armed = false; showCard(CARD_ACTIONS) })
    confirm.add(iconButton(AllIcons.Actions.Checked, t("history.action.delete"), onDelete))
    cards.add(confirm, CARD_CONFIRM)
  }

  private fun iconRow(): JPanel =
    JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(ICON_GAP), 0)).apply { isOpaque = false }

  private fun iconButton(icon: Icon, tooltip: String, onClick: () -> Unit): PillButton =
    PillButton(icon = icon) { onClick() }.apply {
      toolTipText = tooltip
      trackHover(this)
    }

  private fun showCard(name: String) {
    (cards.layout as CardLayout).show(cards, name)
  }

  /**
   * AWT mouse events do not bubble: a child with its own listener (icon button, quote
   * line) swallows enter/exit, so hover is tracked on every interactive component and
   * an exit only clears it when the pointer actually left the row bounds.
   */
  private fun trackHover(component: Component) {
    component.addMouseListener(object : MouseAdapter() {
      override fun mouseEntered(e: MouseEvent) = setHover(true)

      override fun mouseExited(e: MouseEvent) {
        val p = SwingUtilities.convertPoint(e.component, e.point, this@ThreadRow)
        setHover(contains(p))
      }
    })
  }

  private fun setHover(value: Boolean) {
    if (hover == value) return
    hover = value
    if (!value) armed = false
    showCard(if (armed) CARD_CONFIRM else if (hover) CARD_ACTIONS else CARD_BADGE)
    repaint()
  }

  override fun paintComponent(g: Graphics) {
    val g2 = g.create() as Graphics2D
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      if (hover || isCurrent) {
        g2.color = HOVER_BG
        val arc = JBUI.scale(ARC)
        g2.fillRoundRect(0, 0, width, height, arc, arc)
      }
      if (isCurrent) {
        g2.color = ACTIVE_STRIPE
        val stripeWidth = JBUI.scale(STRIPE_WIDTH)
        val stripeHeight = (height * STRIPE_FRACTION).toInt()
        g2.fillRoundRect(0, (height - stripeHeight) / 2, stripeWidth, stripeHeight, stripeWidth, stripeWidth)
      }
    }
    finally {
      g2.dispose()
    }
    super.paintComponent(g)
  }

  companion object {
    private const val ARC = 12
    private const val PAD_V = 8
    private const val PAD_H = 12
    private const val H_GAP = 8
    private const val LINE_GAP = 6
    private const val QUOTE_GAP = 2
    private const val ICON_GAP = 2
    private const val STRIPE_WIDTH = 3
    private const val STRIPE_FRACTION = 0.55f
    private const val QUOTE_FONT_SIZE = 11f
    private const val PROJECT_BADGE_MAX_WIDTH = 120
    private const val CARD_BADGE = "badge"
    private const val CARD_ACTIONS = "actions"
    private const val CARD_CONFIRM = "confirm"

    val HOVER_BG: Color = JBColor.namedColor("Vibe.History.rowHoverBackground", JBColor(0xF0F2F6, 0x252A38))
    val ACTIVE_STRIPE: Color = JBColor.namedColor("Vibe.History.activeStripe", JBColor(0xFC28A8, 0xFC28A8))
    val MUTED: Color = JBColor.namedColor("Vibe.History.quoteForeground", JBColor.namedColor("Label.infoForeground", JBColor.GRAY))
  }
}

/** Small rounded pill for meta text (count badge, project badge, «+N» counter). */
internal class HistoryPill(text: String, maxWidth: Int = 0) : JLabel(text) {
  private val maxWidthPx = if (maxWidth > 0) JBUI.scale(maxWidth) else 0

  init {
    font = JBFont.label().deriveFont(FONT_SIZE)
    foreground = ThreadRow.MUTED
    border = JBUI.Borders.empty(PAD_V, PAD_H)
  }

  override fun getPreferredSize(): Dimension {
    val d = super.getPreferredSize()
    if (maxWidthPx > 0 && d.width > maxWidthPx) d.width = maxWidthPx
    return d
  }

  override fun paintComponent(g: Graphics) {
    val g2 = g.create() as Graphics2D
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g2.color = BG
      g2.fillRoundRect(0, 0, width, height, height, height)
    }
    finally {
      g2.dispose()
    }
    super.paintComponent(g)
  }

  companion object {
    const val FONT_SIZE = 10f
    private const val PAD_V = 2
    private const val PAD_H = 8
    val BG: Color = JBColor.namedColor("Vibe.History.badgeBackground", JBColor(0xE6EEF8, 0x28324A))
  }
}
