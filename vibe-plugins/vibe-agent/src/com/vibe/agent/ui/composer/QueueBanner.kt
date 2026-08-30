// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import com.vibe.agent.i18n.VibeI18n.t

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Yellow strip above the input (VibeIDE §9): notes queued while the agent is busy.
 * A view over [InjectionQueue]; each note shows up to [MAX_LINES] lines, attachment
 * counters and a × that removes just that note.
 */
class QueueBanner(private val queue: InjectionQueue) : JPanel(BorderLayout(0, JBUI.scale(GAP))) {
  private val title = JLabel().apply {
    font = JBFont.label().deriveFont(FONT_SIZE).asBold()
    foreground = FG
  }
  private val rows = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
  }

  init {
    isOpaque = false
    border = JBUI.Borders.empty(PAD_V, PAD_H)
    add(title, BorderLayout.NORTH)
    add(rows, BorderLayout.CENTER)
    queue.onChange { refresh() }
    refresh()
  }

  private fun refresh() {
    rows.removeAll()
    isVisible = !queue.isEmpty
    title.text = "📌 " + t("queue.banner", "count" to queue.size)
    queue.snapshot().forEachIndexed { index, note ->
      rows.add(row(index, note))
    }
    revalidate()
    repaint()
  }

  private fun row(index: Int, note: ComposedMessage): JPanel = JPanel(BorderLayout(JBUI.scale(GAP), 0)).apply {
    isOpaque = false
    alignmentX = LEFT_ALIGNMENT
    val preview = note.text.lines().take(MAX_LINES).joinToString("\n").let {
      if (note.text.lines().size > MAX_LINES) "$it…" else it
    }
    add(JTextArea(preview).apply {
      isEditable = false
      isOpaque = false
      lineWrap = true
      wrapStyleWord = true
      font = JBFont.label().deriveFont(FONT_SIZE)
      foreground = FG
      border = JBUI.Borders.empty()
    }, BorderLayout.CENTER)
    val counters = buildList {
      if (note.images.isNotEmpty()) add("🖼 ${note.images.size}")
      if (note.context.isNotEmpty()) add("📎 ${note.context.size}")
    }
    val east = JPanel(BorderLayout(JBUI.scale(GAP), 0)).apply { isOpaque = false }
    if (counters.isNotEmpty()) east.add(JLabel(counters.joinToString(" · ")).apply {
      font = JBFont.label().deriveFont(FONT_SIZE)
      foreground = FG
    }, BorderLayout.CENTER)
    east.add(JLabel(AllIcons.Actions.Close).apply {
      toolTipText = t("queue.remove")
      cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
      addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) { queue.removeAt(index) }
      })
    }, BorderLayout.EAST)
    add(east, BorderLayout.EAST)
  }

  override fun paintComponent(g: Graphics) {
    val g2 = g.create() as Graphics2D
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      val arc = JBUI.scale(ARC)
      g2.color = BG
      g2.fillRoundRect(0, 0, width, height, arc, arc)
      g2.color = BORDER
      g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
    }
    finally {
      g2.dispose()
    }
    super.paintComponent(g)
  }

  private companion object {
    const val GAP = 4
    const val PAD_V = 6
    const val PAD_H = 8
    const val ARC = 8
    const val MAX_LINES = 3
    const val FONT_SIZE = 11f
    val BG: Color = JBColor.namedColor("Vibe.Composer.queueBackground", JBUI.CurrentTheme.Banner.WARNING_BACKGROUND)
    val BORDER: Color = JBColor.namedColor("Vibe.Composer.queueBorder", JBUI.CurrentTheme.Banner.WARNING_BORDER_COLOR)
    val FG: Color = JBColor.namedColor("Vibe.Composer.queueForeground", JBColor.namedColor("Label.foreground", JBColor.foreground()))
  }
}
