// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import com.vibe.agent.i18n.VibeI18n.t

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Font
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Сворачиваемый блок рассуждения агента: ACP-поток `agent_thought_chunk`, поле `reasoning_content`
 * у OpenAI-совместимых, `thinking` у Anthropic и теги `<think>` внутри ответа ([InlineThinking]).
 *
 * Свёрнут по умолчанию — рассуждение вторично по отношению к ответу, но один клик его открывает.
 *
 * Заголовок обязан говорить, что внутри: пустая надпись «размышления» ничем не отличается от
 * заглушки, и свёрнутый блок выглядит как неработающая кнопка. Пока ход идёт — «думает…», после —
 * объём в знаках, чтобы решение «открывать или нет» принималось до клика (владелец сравнил с
 * VibeIDE, 18.09.2026).
 */
class ThoughtsBlock : JPanel(BorderLayout()) {
  private var collapsed = true
  private var streaming = true
  private val header = JLabel()
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
    header.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    header.toolTipText = t("thoughts.tooltip")
    header.addMouseListener(object : java.awt.event.MouseAdapter() {
      override fun mouseClicked(e: java.awt.event.MouseEvent) {
        collapsed = !collapsed
        area.isVisible = !collapsed
        redrawHeader()
        revalidate(); repaint()
      }
    })
    add(header, BorderLayout.NORTH)
    add(area, BorderLayout.CENTER)
    redrawHeader()
  }

  fun append(text: String) {
    area.text = area.text + text
    redrawHeader()
  }

  /**
   * Ход кончился: заголовок перестаёт обещать продолжение.
   *
   * Блок, навсегда застрявший на «думает…», врёт о состоянии — и именно это видно первым, когда
   * возвращаешься к старому разговору.
   */
  fun finish() {
    streaming = false
    redrawHeader()
  }

  private fun redrawHeader() {
    val chevron = if (collapsed) "▸" else "▾"
    val state = if (streaming) t("thoughts.streaming") else t("thoughts.size", "chars" to area.text.length)
    header.text = "$chevron 💭 " + t("thoughts.title") + "  ·  " + state
  }

  companion object {
    private val FG = JBColor.namedColor("Vibe.Chat.metaForeground", JBColor.GRAY)
  }
}
