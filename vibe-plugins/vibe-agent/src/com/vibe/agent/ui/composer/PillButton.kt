// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.SwingConstants

/**
 * Compact clickable "pill" (VibeIDE composer control): text and/or icon, rounded hover
 * background, optional trailing chevron for dropdowns. Accent variant paints a filled
 * background always (the send button). Colors come from theme tokens only.
 */
class PillButton(
  text: String? = null,
  icon: Icon? = null,
  private val dropdown: Boolean = false,
  private val accent: Boolean = false,
  private val onClick: (PillButton) -> Unit,
) : JLabel() {
  private var hover = false

  init {
    this.text = text
    // On the filled accent circle the stock (light) icon has no contrast: tint it with the accent foreground.
    this.icon = if (accent && icon != null) IconUtil.colorize(icon, ACCENT_FG, keepGray = false, keepBrightness = false) else icon
    if (accent && icon != null) disabledIcon = icon
    font = JBFont.label().deriveFont(FONT_SIZE)
    iconTextGap = JBUI.scale(4)
    horizontalAlignment = SwingConstants.CENTER
    isOpaque = false
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    border = JBUI.Borders.empty(PAD_V, PAD_H, PAD_V, if (dropdown) PAD_H + CHEVRON_SIZE + CHEVRON_GAP else PAD_H)
    foreground = if (accent) ACCENT_FG else PILL_FG
    addMouseListener(object : MouseAdapter() {
      override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
      override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
      override fun mouseClicked(e: MouseEvent) { if (isEnabled && e.button == MouseEvent.BUTTON1) onClick(this@PillButton) }
    })
  }

  override fun getPreferredSize(): Dimension {
    val d = super.getPreferredSize()
    return Dimension(maxOf(d.width, JBUI.scale(MIN_SIZE)), maxOf(d.height, JBUI.scale(MIN_SIZE)))
  }

  override fun paintComponent(g: Graphics) {
    val g2 = g.create() as Graphics2D
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      val arc = height
      val bg: Color? = when {
        !isEnabled -> null
        accent -> if (hover) ACCENT_HOVER else ACCENT
        hover -> HOVER_BG
        else -> null
      }
      if (bg != null) {
        g2.color = bg
        g2.fillRoundRect(0, 0, width, height, arc, arc)
      }
      else if (accent) {
        // Disabled send: an outline only, so the (gray) arrow stays readable.
        g2.color = DISABLED_ACCENT
        g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
      }
      if (dropdown) {
        val chevron = AllIcons.General.ChevronDown
        chevron.paintIcon(this, g2, width - JBUI.scale(PAD_H) - chevron.iconWidth, (height - chevron.iconHeight) / 2)
      }
    }
    finally {
      g2.dispose()
    }
    super.paintComponent(g)
  }

  companion object {
    private const val FONT_SIZE = 12f
    private const val PAD_V = 3
    private const val PAD_H = 8
    private const val MIN_SIZE = 24
    private const val CHEVRON_SIZE = 12
    private const val CHEVRON_GAP = 2

    val PILL_FG: Color = JBColor.namedColor("Vibe.Composer.pillForeground", JBColor.namedColor("Label.foreground", JBColor.foreground()))
    val HOVER_BG: Color = JBColor.namedColor("Vibe.Composer.pillHoverBackground", JBUI.CurrentTheme.ActionButton.hoverBackground())
    val ACCENT: Color = JBColor.namedColor("Vibe.Composer.accent", JBColor(0x3574F0, 0x3574F0))
    val ACCENT_HOVER: Color = JBColor.namedColor("Vibe.Composer.accentHover", JBColor(0x2B64D6, 0x4A84F5))
    val ACCENT_FG: Color = JBColor.namedColor("Vibe.Composer.accentForeground", JBColor(0xFFFFFF, 0x16171B))
    val DISABLED_ACCENT: Color = JBColor.namedColor("Vibe.Composer.accentDisabled", JBColor(0xC9D4EA, 0x4A5068))
  }
}
