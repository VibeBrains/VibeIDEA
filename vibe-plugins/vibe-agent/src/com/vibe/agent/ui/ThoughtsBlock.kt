// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Collapsible "thinking" block for the agent's reasoning stream
 * (ACP `agent_thought_chunk`, previously dropped on the floor). Dim and collapsed
 * by default — reasoning is secondary to the answer, but one click reveals it.
 */
class ThoughtsBlock : JPanel(BorderLayout()) {
  private var collapsed = true
  private val header = JLabel("▸ 💭 размышления")
  private val area = JTextArea().apply {
    isEditable = false
    isOpaque = false
    lineWrap = true
    wrapStyleWord = true
    font = com.intellij.util.ui.JBFont.label().deriveFont(Font.ITALIC, 12f)
    foreground = FG
    border = JBUI.Borders.empty(2, 10, 4, 4)
    isVisible = false
  }

  init {
    isOpaque = false
    alignmentX = Component.LEFT_ALIGNMENT
    border = JBUI.Borders.empty(1, 4)
    header.font = com.intellij.util.ui.JBFont.label().deriveFont(Font.PLAIN, 11f)
    header.foreground = FG
    header.border = JBUI.Borders.empty(3, 6)
    header.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
    header.toolTipText = "Показать/скрыть размышления агента"
    header.addMouseListener(object : java.awt.event.MouseAdapter() {
      override fun mouseClicked(e: java.awt.event.MouseEvent) {
        collapsed = !collapsed
        area.isVisible = !collapsed
        header.text = (if (collapsed) "▸" else "▾") + " 💭 размышления"
        revalidate(); repaint()
      }
    })
    add(header, BorderLayout.NORTH)
    add(area, BorderLayout.CENTER)
  }

  fun append(text: String) {
    area.text = area.text + text
    area.caretPosition = area.document.length
  }

  companion object {
    private val FG = JBColor.namedColor("Vibe.Chat.metaForeground", JBColor.GRAY)
  }
}
