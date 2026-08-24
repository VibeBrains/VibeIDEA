// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/**
 * Chat tab strip (VibeIDE §8): a 34px row above the feed — one tab per open thread
 * (label = the thread title trimmed to 22 chars, «Новый чат» for an empty one, full
 * title in the tooltip, × on hover when there is more than one tab), a «+» that always
 * creates a fresh thread, and the history-rail toggle on the right.
 */
class ChatTabsStrip(private val callbacks: Callbacks) : JPanel(BorderLayout()) {
  interface Callbacks {
    fun onSelect(threadId: String)
    fun onClose(threadId: String)
    fun onNewChat()
    fun onToggleRail()
  }

  class TabInfo(val id: String, val label: String, val tooltip: String)

  private val tabsRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }
  private val railButton = JLabel(AllIcons.Vcs.History).apply {
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    border = JBUI.Borders.empty(0, SIDE_PAD)
    addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) { callbacks.onToggleRail() }
    })
  }

  init {
    isOpaque = false
    preferredSize = Dimension(0, JBUI.scale(STRIP_HEIGHT))
    border = JBUI.Borders.customLineBottom(SEPARATOR)
    val scroll = JBScrollPane(tabsRow).apply {
      border = JBUI.Borders.empty()
      horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
      verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
      isOpaque = false
      viewport.isOpaque = false
    }
    // No visible bar, but overflowing tabs must stay reachable: the wheel pans the strip.
    scroll.addMouseWheelListener { e ->
      val viewport = scroll.viewport
      val max = (tabsRow.preferredSize.width - viewport.width).coerceAtLeast(0)
      if (max > 0) {
        val position = viewport.viewPosition
        position.x = (position.x + e.wheelRotation * JBUI.scale(WHEEL_STEP)).coerceIn(0, max)
        viewport.viewPosition = position
      }
    }
    add(scroll, BorderLayout.CENTER)
    add(railButton, BorderLayout.EAST)
  }

  fun update(tabs: List<TabInfo>, activeId: String?, railOpen: Boolean) {
    tabsRow.removeAll()
    for (tab in tabs) {
      tabsRow.add(Tab(tab, active = tab.id == activeId, closable = tabs.size > 1))
    }
    tabsRow.add(JLabel(AllIcons.General.Add).apply {
      toolTipText = "Новый чат"
      cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
      border = JBUI.Borders.empty(TAB_PAD_V, TAB_PAD_H)
      addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) { callbacks.onNewChat() }
      })
    })
    railButton.toolTipText = if (railOpen) "Свернуть историю (оставить только чат)" else "Показать историю"
    revalidate()
    repaint()
  }

  private inner class Tab(info: TabInfo, private val active: Boolean, closable: Boolean) : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)) {
    private var hover = false
    private val close = JLabel(AllIcons.Actions.Close).apply {
      toolTipText = "Закрыть вкладку (чат останется в истории)"
      cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
      isVisible = false
      addMouseListener(object : MouseAdapter() {
        override fun mouseEntered(e: MouseEvent) { setIcon(AllIcons.Actions.CloseHovered) }
        override fun mouseExited(e: MouseEvent) { setIcon(AllIcons.Actions.Close) }
        override fun mouseClicked(e: MouseEvent) {
          e.consume()
          callbacks.onClose(info.id)
        }
      })
    }

    init {
      isOpaque = false
      toolTipText = info.tooltip.ifBlank { info.label }
      border = JBUI.Borders.empty(TAB_PAD_V, TAB_PAD_H)
      cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
      add(JLabel(info.label).apply {
        font = JBFont.label().deriveFont(FONT_SIZE)
        foreground = if (active) ACTIVE_FG else INACTIVE_FG
      })
      if (closable) add(close)
      val listener = object : MouseAdapter() {
        override fun mouseEntered(e: MouseEvent) { hover = true; close.isVisible = closable; revalidate(); repaint() }
        override fun mouseExited(e: MouseEvent) {
          // Moving onto the × keeps the tab hovered; a real exit hides it.
          if (!contains(e.point)) { hover = false; close.isVisible = false; revalidate(); repaint() }
        }
        override fun mouseClicked(e: MouseEvent) { if (!e.isConsumed) callbacks.onSelect(info.id) }
      }
      addMouseListener(listener)
    }

    override fun paintComponent(g: Graphics) {
      val g2 = g.create() as Graphics2D
      try {
        if (active || hover) {
          g2.color = if (active) ACTIVE_BG else HOVER_BG
          g2.fillRect(0, 0, width, height)
        }
        if (active) {
          g2.color = UNDERLINE
          g2.fillRect(0, height - JBUI.scale(UNDERLINE_HEIGHT), width, JBUI.scale(UNDERLINE_HEIGHT))
        }
      }
      finally {
        g2.dispose()
      }
      super.paintComponent(g)
    }
  }

  companion object {
    /** VibeIDE: the tab label is the first 22 chars of the first user message. */
    const val LABEL_LIMIT = 22
    const val EMPTY_LABEL = "Новый чат"

    fun label(title: String): String = when {
      title.isBlank() -> EMPTY_LABEL
      title.length <= LABEL_LIMIT -> title
      else -> title.take(LABEL_LIMIT) + "…"
    }

    private const val STRIP_HEIGHT = 34
    private const val TAB_PAD_V = 7
    private const val TAB_PAD_H = 10
    private const val SIDE_PAD = 8
    private const val UNDERLINE_HEIGHT = 2
    private const val WHEEL_STEP = 40
    private const val FONT_SIZE = 12f

    val ACTIVE_FG: Color = JBColor.namedColor("Vibe.Tabs.activeForeground", JBColor.namedColor("Label.foreground", JBColor.foreground()))
    val INACTIVE_FG: Color = JBColor.namedColor("Vibe.Tabs.inactiveForeground", JBColor.namedColor("Label.infoForeground", JBColor.GRAY))
    val ACTIVE_BG: Color = JBColor.namedColor("Vibe.Tabs.activeBackground", JBColor(0xF0F2F6, 0x252A38))
    val HOVER_BG: Color = JBColor.namedColor("Vibe.Tabs.hoverBackground", JBColor(0xF6F7FA, 0x20242E))
    val UNDERLINE: Color = JBColor.namedColor("Vibe.Tabs.underline", JBColor(0x3574F0, 0x00B8CC))
    val SEPARATOR: Color = JBColor.namedColor("Vibe.Composer.separator", JBColor.namedColor("Separator.separatorColor", JBColor.border()))
  }
}
