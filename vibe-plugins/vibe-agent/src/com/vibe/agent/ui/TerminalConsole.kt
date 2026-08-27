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
 * A compact live console for one agent terminal (the Claude adapter streams Bash
 * output through `_meta.terminal_output`). Monospace, ANSI stripped, head+tail
 * capped so a runaway command cannot grow the feed without bound. This is a
 * READ-ONLY view: the command runs inside the agent, we only mirror its bytes.
 */
class TerminalConsole(title: String) : JPanel(BorderLayout()) {
  private val titleText = title.take(120)
  private var exitBadge: String? = null
  private var collapsed = false
  private val header = JLabel("▾ ▹ $titleText")
  private val copy = JLabel("копировать").apply {
    font = com.intellij.util.ui.JBFont.label().deriveFont(Font.PLAIN, 10f)
    foreground = JBColor.namedColor("Vibe.Chat.metaForeground", JBColor.GRAY)
    border = JBUI.Borders.empty(3, 6)
    cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
    toolTipText = "Скопировать вывод терминала"
  }
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
    header.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
    header.toolTipText = "Свернуть/развернуть вывод"
    header.addMouseListener(object : java.awt.event.MouseAdapter() {
      override fun mouseClicked(e: java.awt.event.MouseEvent) = toggleCollapsed()
    })
    copy.addMouseListener(object : java.awt.event.MouseAdapter() {
      override fun mouseClicked(e: java.awt.event.MouseEvent) {
        CopyPasteManager.getInstance().setContents(StringSelection(raw.toString()))
      }
    })
    val bar = JPanel(BorderLayout()).apply {
      isOpaque = false
      add(header, BorderLayout.WEST)
      add(copy, BorderLayout.EAST)
    }
    add(bar, BorderLayout.NORTH)
    add(area, BorderLayout.CENTER)
  }

  private fun toggleCollapsed() {
    collapsed = !collapsed
    area.isVisible = !collapsed
    refreshHeader()
    revalidate(); repaint()
  }

  private fun refreshHeader() {
    val arrow = if (collapsed) "▸" else "▾"
    val mark = if (exitBadge != null) "▪" else "▹"
    header.text = "$arrow $mark $titleText" + (exitBadge?.let { "  ·  $it" } ?: "")
  }

  /** Append a streamed chunk (called on the EDT). */
  fun append(data: String) {
    raw.append(stripAnsi(data))
    // Cap the BACKING buffer too, or a chatty long-running command grows it without bound
    // (and «копировать» would then copy megabytes). Keep head+tail, collapse the middle.
    if (raw.length > RAW_CAP) {
      val head = raw.substring(0, DISPLAY_CAP / 2)
      val tail = raw.substring(raw.length - DISPLAY_CAP / 2)
      raw.setLength(0)
      raw.append(head).append("\n…\n").append(tail)
    }
    render()
  }

  fun markExit(exitCode: Int?, signal: String?) {
    exitBadge = when {
      signal != null -> "убит ($signal)"
      exitCode == 0 -> "завершено (0)"
      exitCode != null -> "код $exitCode"
      else -> "завершено"
    }
    refreshHeader()
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
    /** Hard ceiling on the backing buffer (head+tail retained above this). */
    private const val RAW_CAP = DISPLAY_CAP * 2
    private val ANSI = Regex("\\[[0-9;?]*[ -/]*[@-~]")
    private fun stripAnsi(s: String): String = ANSI.replace(s, "").replace("\r\n", "\n").replace('\r', '\n')

    private val CONSOLE_BG = JBColor.namedColor("Vibe.Chat.terminalBackground", JBColor(0xF7F7F7, 0x1E1F22))
    private val CONSOLE_FG = JBColor.namedColor("Vibe.Chat.terminalForeground", JBColor(0x2B2B2B, 0xBCBEC4))
    private val BORDER = JBColor.namedColor("Vibe.Chat.terminalBorder", JBColor(0xD8D8D8, 0x2B2D30))
    private val OK_FG = JBColor.namedColor("Vibe.Chat.terminalOk", JBColor(0x3A8A3A, 0x5FAD5F))
    private val ERR_FG = JBColor.namedColor("Vibe.Chat.terminalError", JBColor(0xC0392B, 0xE06C5A))
  }
}
