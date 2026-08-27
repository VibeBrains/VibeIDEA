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
 * A compact live console for one agent terminal (the Claude adapter streams Bash
 * output through `_meta.terminal_output`). Monospace, ANSI stripped, head+tail
 * capped so a runaway command cannot grow the feed without bound. This is a
 * READ-ONLY view: the command runs inside the agent, we only mirror its bytes.
 */
class TerminalConsole(title: String) : JPanel(BorderLayout()) {
  private val header = JLabel("▹ ${title.take(120)}")
  private val area = JTextArea().apply {
    isEditable = false
    font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(11f))
    lineWrap = true
    wrapStyleWord = false
    background = CONSOLE_BG
    foreground = CONSOLE_FG
    border = JBUI.Borders.empty(4, 6)
    rows = 1
  }
  private val raw = StringBuilder()

  init {
    isOpaque = false
    alignmentX = Component.LEFT_ALIGNMENT
    border = JBUI.Borders.compound(JBUI.Borders.empty(2, 4), JBUI.Borders.customLine(BORDER, 1))
    header.font = com.intellij.util.ui.JBFont.label().deriveFont(Font.PLAIN, 11f)
    header.foreground = JBColor.namedColor("Vibe.Chat.metaForeground", JBColor.GRAY)
    header.border = JBUI.Borders.empty(3, 6)
    add(header, BorderLayout.NORTH)
    add(area, BorderLayout.CENTER)
  }

  /** Append a streamed chunk (called on the EDT). */
  fun append(data: String) {
    raw.append(stripAnsi(data))
    render()
  }

  fun markExit(exitCode: Int?, signal: String?) {
    val badge = when {
      signal != null -> "убит ($signal)"
      exitCode == 0 -> "завершено (0)"
      exitCode != null -> "код $exitCode"
      else -> "завершено"
    }
    header.text = "▪ ${header.text.removePrefix("▹ ").removePrefix("▪ ")}  ·  $badge"
    header.foreground = if (exitCode == 0) OK_FG else if (exitCode != null || signal != null) ERR_FG else header.foreground
  }

  private fun render() {
    val text = raw.toString()
    area.text = if (text.length > DISPLAY_CAP) {
      text.take(DISPLAY_CAP / 2) + "\n…\n" + text.takeLast(DISPLAY_CAP / 2)
    } else text
    area.caretPosition = area.document.length
  }

  companion object {
    /** VibeIDE reads 80 000 chars of terminal output; the live view mirrors that cap. */
    const val DISPLAY_CAP = 80_000
    private val ANSI = Regex("\\[[0-9;?]*[ -/]*[@-~]")
    private fun stripAnsi(s: String): String = ANSI.replace(s, "").replace("\r\n", "\n").replace('\r', '\n')

    private val CONSOLE_BG = JBColor.namedColor("Vibe.Chat.terminalBackground", JBColor(0xF7F7F7, 0x1E1F22))
    private val CONSOLE_FG = JBColor.namedColor("Vibe.Chat.terminalForeground", JBColor(0x2B2B2B, 0xBCBEC4))
    private val BORDER = JBColor.namedColor("Vibe.Chat.terminalBorder", JBColor(0xD8D8D8, 0x2B2D30))
    private val OK_FG = JBColor.namedColor("Vibe.Chat.terminalOk", JBColor(0x3A8A3A, 0x5FAD5F))
    private val ERR_FG = JBColor.namedColor("Vibe.Chat.terminalError", JBColor(0xC0392B, 0xE06C5A))
  }
}
