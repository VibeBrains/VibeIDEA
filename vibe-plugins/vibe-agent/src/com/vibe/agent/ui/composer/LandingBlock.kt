// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.WrapLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Empty-chat landing under the composer (VibeIDE §7): informational chips «Файл …» /
 * «Модель Провайдер:модель» (not buttons), then either «Прошлые чаты» (when the history
 * holds more than one thread — the caller passes the list component) or the «Подсказки»
 * prompt buttons. Quick actions (Объяснить / Рефакторинг …) wait for their IDE commands.
 */
class LandingBlock(pastChats: JComponent, private val onPrompt: (String) -> Unit) : JPanel() {
  private val chips = JPanel(WrapLayout(FlowLayout.CENTER, JBUI.scale(GAP), JBUI.scale(GAP))).apply { isOpaque = false }
  private val suggestionsSection = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
    add(sectionTitle("Подсказки"))
    SUGGESTIONS.forEach { text ->
      add(PillButton(text = text) { onPrompt(text) }.apply {
        alignmentX = Component.LEFT_ALIGNMENT
        horizontalAlignment = JLabel.LEFT
        foreground = MUTED
        maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
      })
    }
  }
  private val pastChatsSection = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
    add(sectionTitle("Прошлые чаты"))
    add(pastChats.apply { alignmentX = Component.LEFT_ALIGNMENT })
    isVisible = false
  }

  init {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
    border = JBUI.Borders.empty(TOP_PAD, SIDE_PAD, 0, SIDE_PAD)
    add(chips.apply { alignmentX = Component.LEFT_ALIGNMENT })
    add(pastChatsSection.apply { alignmentX = Component.LEFT_ALIGNMENT })
    add(suggestionsSection.apply { alignmentX = Component.LEFT_ALIGNMENT })
  }

  private fun sectionTitle(text: String): JLabel = JLabel(text).apply {
    font = JBFont.label().deriveFont(TITLE_FONT_SIZE).asBold()
    foreground = MUTED
    alignmentX = Component.LEFT_ALIGNMENT
    border = JBUI.Borders.empty(SECTION_GAP, 0, GAP, 0)
  }

  fun update(activeFileName: String?, modelLabel: String?, showPastChats: Boolean) {
    chips.removeAll()
    activeFileName?.let { chips.add(InfoChip("Файл", it)) }
    modelLabel?.let { chips.add(InfoChip("Модель", it)) }
    chips.isVisible = chips.componentCount > 0
    pastChatsSection.isVisible = showPastChats
    suggestionsSection.isVisible = !showPastChats
    revalidate()
    repaint()
  }

  /** Non-interactive chip: muted key + value. */
  private class InfoChip(key: String, value: String) : JLabel() {
    init {
      // Swing HTML knows no opacity: the key is muted with an explicit color.
      text = "<html><font color='#${ColorUtil.toHex(MUTED)}'>$key</font>&nbsp;&nbsp;$value</html>"
      font = JBFont.label().deriveFont(CHIP_FONT_SIZE)
      foreground = Chip.CHIP_FG
      border = JBUI.Borders.empty(CHIP_PAD_V, CHIP_PAD_H)
    }

    override fun paintComponent(g: Graphics) {
      val g2 = g.create() as Graphics2D
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = Chip.CHIP_BG
        g2.fillRoundRect(0, 0, width, height, height, height)
      }
      finally {
        g2.dispose()
      }
      super.paintComponent(g)
    }
  }

  companion object {
    val SUGGESTIONS = listOf(
      "Кратко опиши мой проект",
      "Как устроены типы в этом проекте?",
      "Помоги написать AGENTS.md для этого репозитория",
      "Прикрепить правила агента (@agent)",
    )
    private const val GAP = 6
    private const val SECTION_GAP = 16
    private const val TOP_PAD = 4
    private const val SIDE_PAD = 10
    private const val TITLE_FONT_SIZE = 12f
    private const val CHIP_FONT_SIZE = 12f
    private const val CHIP_PAD_V = 3
    private const val CHIP_PAD_H = 10
    val MUTED: Color = JBColor.namedColor("Vibe.Chat.metaForeground", JBColor.namedColor("Label.infoForeground", JBColor.GRAY))
  }
}
