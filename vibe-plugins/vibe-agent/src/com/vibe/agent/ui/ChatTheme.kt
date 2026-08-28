// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Font
import java.awt.datatransfer.StringSelection
import javax.swing.JLabel

/**
 * Single source of the shared chat-surface theme tokens (terminal/code/meta) and
 * the small UI affordances reused across panels. Every fallback hex lives HERE
 * once — TerminalConsole, CodeBlockPanel and the copy links reference these, so
 * the surfaces can never drift apart.
 */
internal object ChatTheme {
  val TERMINAL_BG = JBColor.namedColor("Vibe.Chat.terminalBackground", JBColor(0xF7F7F7, 0x1E1F22))
  val TERMINAL_FG = JBColor.namedColor("Vibe.Chat.terminalForeground", JBColor(0x2B2B2B, 0xBCBEC4))
  val TERMINAL_BORDER = JBColor.namedColor("Vibe.Chat.terminalBorder", JBColor(0xD8D8D8, 0x2B2D30))
  val TERMINAL_OK = JBColor.namedColor("Vibe.Chat.terminalOk", JBColor(0x3A8A3A, 0x5FAD5F))
  val TERMINAL_ERR = JBColor.namedColor("Vibe.Chat.terminalError", JBColor(0xC0392B, 0xE06C5A))
  val META_FG = JBColor.namedColor("Vibe.Chat.metaForeground", JBColor.namedColor("Label.infoForeground", JBColor.GRAY))
  val CARD_BG = JBColor.namedColor("Vibe.Chat.toolCardBackground", JBColor(0xF2F2F2, 0x26282E))

  /** Caption font size for small affordances (copy links, block headers). */
  const val CAPTION_FONT_PT = 10f

  /** A quiet clickable «копировать» label; [supplier] provides the text at click time. */
  fun copyLabel(tooltip: String, supplier: () -> String): JLabel = JLabel("копировать").apply {
    font = com.intellij.util.ui.JBFont.label().deriveFont(Font.PLAIN, CAPTION_FONT_PT)
    foreground = META_FG
    border = JBUI.Borders.empty(2, 8)
    cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
    toolTipText = tooltip
    addMouseListener(object : java.awt.event.MouseAdapter() {
      override fun mouseClicked(e: java.awt.event.MouseEvent) {
        CopyPasteManager.getInstance().setContents(StringSelection(supplier()))
      }
    })
  }
}
