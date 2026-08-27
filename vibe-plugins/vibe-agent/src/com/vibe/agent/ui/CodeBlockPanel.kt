// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.datatransfer.StringSelection
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * A monospace code block for an agent message's fenced ``` section: theme-colored
 * surface, an optional language tag, and its own «копировать» button. Reuses the
 * terminal-console theme tokens so code and terminal read as one surface.
 */
class CodeBlockPanel(lang: String?, code: String) : JPanel(BorderLayout()) {
  init {
    isOpaque = false
    alignmentX = Component.LEFT_ALIGNMENT
    border = JBUI.Borders.compound(JBUI.Borders.empty(2, 0), JBUI.Borders.customLine(BORDER, 1))

    val header = JPanel(BorderLayout()).apply {
      isOpaque = false
      background = HEADER_BG
      isOpaque = true
      add(JLabel(lang?.lowercase() ?: "код").apply {
        font = com.intellij.util.ui.JBFont.label().deriveFont(Font.PLAIN, 10f)
        foreground = FG
        border = JBUI.Borders.empty(2, 8)
      }, BorderLayout.WEST)
      add(JLabel("копировать").apply {
        font = com.intellij.util.ui.JBFont.label().deriveFont(Font.PLAIN, 10f)
        foreground = FG
        border = JBUI.Borders.empty(2, 8)
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        toolTipText = "Скопировать код"
        addMouseListener(object : java.awt.event.MouseAdapter() {
          override fun mouseClicked(e: java.awt.event.MouseEvent) {
            CopyPasteManager.getInstance().setContents(StringSelection(code))
          }
        })
      }, BorderLayout.EAST)
    }

    val area = JTextArea(code).apply {
      isEditable = false
      font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12f))
      background = BG
      foreground = FG_CODE
      lineWrap = false
      border = JBUI.Borders.empty(6, 8)
    }
    add(header, BorderLayout.NORTH)
    // Horizontal scroll for long lines instead of wrapping code.
    add(com.intellij.ui.components.JBScrollPane(area).apply {
      horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
      verticalScrollBarPolicy = javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
      border = JBUI.Borders.empty()
      isOpaque = false
      viewport.isOpaque = false
    }, BorderLayout.CENTER)
  }

  companion object {
    private val BG = JBColor.namedColor("Vibe.Chat.terminalBackground", JBColor(0xF7F7F7, 0x1E1F22))
    private val HEADER_BG = JBColor.namedColor("Vibe.Chat.toolCardBackground", JBColor(0xF2F2F2, 0x26282E))
    private val FG_CODE = JBColor.namedColor("Vibe.Chat.terminalForeground", JBColor(0x2B2B2B, 0xBCBEC4))
    private val BORDER = JBColor.namedColor("Vibe.Chat.terminalBorder", JBColor(0xD8D8D8, 0x2B2D30))
    private val FG = JBColor.namedColor("Vibe.Chat.metaForeground", JBColor.GRAY)
  }
}
