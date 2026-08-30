// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import com.vibe.agent.i18n.VibeI18n.t

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.WrapLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * A removable chip: icon + label + ×. Clicking the body runs [onOpen] (open file /
 * selection / folder), the × runs [onRemove]. Used for staged context and attachments.
 */
class Chip(
  icon: Icon?,
  text: String,
  tooltip: String?,
  private val onOpen: (() -> Unit)?,
  onRemove: () -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), 0)) {
  private val close = JLabel(AllIcons.Actions.Close).apply {
    toolTipText = t("chips.remove")
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    addMouseListener(object : MouseAdapter() {
      override fun mouseEntered(e: MouseEvent) { setIcon(AllIcons.Actions.CloseHovered) }
      override fun mouseExited(e: MouseEvent) { setIcon(AllIcons.Actions.Close) }
      override fun mouseClicked(e: MouseEvent) { onRemove() }
    })
  }

  init {
    isOpaque = false
    border = JBUI.Borders.empty(PAD_V, PAD_H)
    toolTipText = tooltip
    val label = JLabel(text, icon, JLabel.LEFT).apply {
      font = JBFont.label().deriveFont(FONT_SIZE)
      foreground = CHIP_FG
      if (onOpen != null) cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
      addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) { if (e.button == MouseEvent.BUTTON1) onOpen?.invoke() }
      })
    }
    add(label)
    add(close)
  }

  override fun paintComponent(g: Graphics) {
    val g2 = g.create() as Graphics2D
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g2.color = CHIP_BG
      val arc = JBUI.scale(ARC)
      g2.fillRoundRect(0, 0, width, height, arc, arc)
    }
    finally {
      g2.dispose()
    }
    super.paintComponent(g)
  }

  companion object {
    private const val GAP = 4
    private const val PAD_V = 2
    private const val PAD_H = 6
    private const val ARC = 10
    private const val FONT_SIZE = 12f
    val CHIP_BG: Color = JBColor.namedColor("Vibe.Composer.chipBackground", JBColor(0xE6EEF8, 0x28324A))
    val CHIP_FG: Color = JBColor.namedColor("Vibe.Composer.chipForeground", JBColor.namedColor("Label.foreground", JBColor.foreground()))
  }
}

/** Wrapping strip of chips; hidden while empty so the composer does not reserve a blank line. */
class ChipStrip : JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(GAP))) {
  init {
    isOpaque = false
    border = JBUI.Borders.empty(0, 0, GAP, 0)
    isVisible = false
  }

  fun setChips(chips: List<Chip>) {
    removeAll()
    chips.forEach { add(it) }
    isVisible = chips.isNotEmpty()
    revalidate()
    repaint()
  }

  private companion object {
    const val GAP = 4
  }
}
