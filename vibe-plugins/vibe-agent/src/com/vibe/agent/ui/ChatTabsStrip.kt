// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import com.vibe.agent.i18n.VibeI18n.t

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

/**
 * Chat tab strip (VibeIDE §8): a 34px row above the feed — one tab per open thread
 * (label = the thread title trimmed to 22 chars, «Новый чат» for an empty one, full
 * title in the tooltip, × on hover when there is more than one tab), a «+» that always
 * creates a fresh thread, and the history-rail toggle on the right.
 *
 * The × lies over the right edge of the tab and takes no room
 * A tab keeps its width when the pointer comes onto it, and its neighbours stay where they are
 * The label fades out under the × with a mask
 * A tab also closes on Delete or Backspace while it has the keyboard focus, and on a middle click
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

  /** The tabs as last shown, in order: a closed tab hands the keyboard focus to the one beside it */
  private var shown: List<TabInfo> = emptyList()
  private var focusAfterUpdate: String? = null
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
    val scroll = com.vibe.agent.ui.VibeScroll.pane(tabsRow).apply {
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
    shown = tabs
    val toFocus = focusAfterUpdate
    focusAfterUpdate = null
    for (tab in tabs) {
      val view = Tab(tab, active = tab.id == activeId, closable = ChatTabInput.closable(tabs.size))
      tabsRow.add(view)
      if (tab.id == toFocus) SwingUtilities.invokeLater { view.requestFocusInWindow() }
    }
    tabsRow.add(JLabel(AllIcons.General.Add).apply {
      toolTipText = t("tabs.newChat")
      cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
      border = JBUI.Borders.empty(TAB_PAD_V, TAB_PAD_H)
      addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) { callbacks.onNewChat() }
      })
    })
    railButton.toolTipText = if (railOpen) t("tabs.collapseHistory") else t("tabs.showHistory")
    revalidate()
    repaint()
  }

  private inner class Tab(private val info: TabInfo, private val active: Boolean, private val closable: Boolean) : JPanel(null) {
    private var hover = false
    private val title = JLabel(info.label).apply {
      font = JBFont.label().deriveFont(FONT_SIZE)
      foreground = if (active) ACTIVE_FG else INACTIVE_FG
    }
    private val close = CloseMask()

    init {
      isOpaque = false
      isFocusable = true
      toolTipText = info.tooltip.ifBlank { info.label }
      border = JBUI.Borders.empty(TAB_PAD_V, TAB_PAD_H)
      cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
      // The × first: the first child is painted last and gets the mouse first, so it lies over the label
      add(close)
      add(title)
      val listener = object : MouseAdapter() {
        override fun mouseEntered(e: MouseEvent) { hover = true; refreshClose() }
        override fun mouseExited(e: MouseEvent) {
          // Moving onto the × keeps the tab hovered; a real exit hides it.
          if (!contains(e.point)) { hover = false; refreshClose() }
        }
        override fun mouseReleased(e: MouseEvent) {
          if (ChatTabInput.closeByMouse(e.button, closable) && contains(e.point)) callbacks.onClose(info.id)
        }
        override fun mouseClicked(e: MouseEvent) { if (!e.isConsumed && e.button == MouseEvent.BUTTON1) callbacks.onSelect(info.id) }
      }
      addMouseListener(listener)
      addFocusListener(object : FocusAdapter() {
        override fun focusGained(e: FocusEvent) = refreshClose()
        override fun focusLost(e: FocusEvent) = refreshClose()
      })
      for (key in ChatTabInput.CLOSE_KEYS) {
        getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), CLOSE_ACTION)
      }
      actionMap.put(CLOSE_ACTION, object : AbstractAction() {
        override fun actionPerformed(e: ActionEvent) {
          if (!closable) return
          focusAfterUpdate = ChatTabInput.neighbourAfterClose(shown.map { it.id }, info.id)
          callbacks.onClose(info.id)
        }
      })
    }

    private fun refreshClose() {
      close.isVisible = ChatTabInput.closeVisible(hover, isFocusOwner, closable)
      repaint()
    }

    /** The colour the tab is filled with right now: the × mask has to match it */
    private fun fill(): Color? = if (active) ACTIVE_BG else if (hover || isFocusOwner) HOVER_BG else null

    /** The × takes no room: the tab is as wide as its label, so it keeps its width whether the pointer is on it or not */
    override fun getPreferredSize(): Dimension {
      val insets = insets
      val label = title.preferredSize
      return Dimension(label.width + insets.left + insets.right + 2 * JBUI.scale(LABEL_GAP), label.height + insets.top + insets.bottom)
    }

    override fun doLayout() {
      val insets = insets
      val gap = JBUI.scale(LABEL_GAP)
      title.setBounds(insets.left + gap, insets.top, width - insets.left - insets.right - 2 * gap, height - insets.top - insets.bottom)
      val icon = AllIcons.Actions.Close.iconWidth
      val mask = JBUI.scale(MASK_WIDTH)
      val room = icon + mask
      close.setBounds(width - room - JBUI.scale(CLOSE_INSET), 0, room, height)
    }

    override fun paintComponent(g: Graphics) {
      val g2 = g.create() as Graphics2D
      try {
        fill()?.let {
          g2.color = it
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

    override fun paintChildren(g: Graphics) {
      super.paintChildren(g)
      // A keyboard-focused tab says so: it is the one Delete will close
      if (isFocusOwner) {
        g.color = UNDERLINE
        g.drawRect(0, 0, width - 1, height - 1)
      }
    }

    /** The ×, with a mask on its left that fades the label out so the glyph does not sit on top of letters */
    private inner class CloseMask : JLabel(AllIcons.Actions.Close) {
      init {
        toolTipText = t("tabs.closeTab")
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isVisible = false
        horizontalAlignment = JLabel.RIGHT
        addMouseListener(object : MouseAdapter() {
          override fun mouseEntered(e: MouseEvent) { setIcon(AllIcons.Actions.CloseHovered) }
          override fun mouseExited(e: MouseEvent) { setIcon(AllIcons.Actions.Close) }
          override fun mouseClicked(e: MouseEvent) {
            e.consume()
            callbacks.onClose(info.id)
          }
        })
      }

      override fun paintComponent(g: Graphics) {
        val fill = fill()
        if (fill != null) {
          val g2 = g.create() as Graphics2D
          try {
            val mask = JBUI.scale(MASK_WIDTH)
            g2.paint = GradientPaint(0f, 0f, Color(fill.red, fill.green, fill.blue, 0), mask.toFloat(), 0f, fill)
            g2.fillRect(0, 0, mask, height)
            g2.color = fill
            g2.fillRect(mask, 0, width - mask, height)
          }
          finally {
            g2.dispose()
          }
        }
        super.paintComponent(g)
      }
    }
  }

  companion object {
    /** VibeIDE: the tab label is the first 22 chars of the first user message. */
    const val LABEL_LIMIT = 22
    val EMPTY_LABEL: String get() = t("tabs.newChat")

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

    /** Space the label keeps on both sides, beyond the tab's own padding */
    private const val LABEL_GAP = 2

    /** The × sits this far from the tab's right edge, and the label fades out over this width on its left */
    private const val CLOSE_INSET = 4
    private const val MASK_WIDTH = 10
    private const val CLOSE_ACTION = "vibe.closeTab"

    val ACTIVE_FG: Color = JBColor.namedColor("Vibe.Tabs.activeForeground", JBColor.namedColor("Label.foreground", JBColor.foreground()))
    val INACTIVE_FG: Color = JBColor.namedColor("Vibe.Tabs.inactiveForeground", JBColor.namedColor("Label.infoForeground", JBColor.GRAY))
    val ACTIVE_BG: Color = JBColor.namedColor("Vibe.Tabs.activeBackground", JBColor(0xF0F2F6, 0x252A38))
    val HOVER_BG: Color = JBColor.namedColor("Vibe.Tabs.hoverBackground", JBColor(0xF6F7FA, 0x20242E))
    val UNDERLINE: Color = JBColor.namedColor("Vibe.Tabs.underline", JBColor(0x3574F0, 0x00B8CC))
    val SEPARATOR: Color = JBColor.namedColor("Vibe.Composer.separator", JBColor.namedColor("Separator.separatorColor", JBColor.border()))
  }
}

/**
 * What closes a tab and when the × shows, apart from the components that draw it
 *
 * The single tab of the strip cannot be closed: the chat is never left without a thread
 */
internal object ChatTabInput {
  /** Keys that close the focused tab */
  val CLOSE_KEYS = intArrayOf(KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE)

  fun closable(tabCount: Int): Boolean = tabCount > 1

  /** The middle button closes, the way it does on the tabs of an editor */
  fun closeByMouse(button: Int, closable: Boolean): Boolean = closable && button == MouseEvent.BUTTON2

  /** The × shows under the pointer and on the focused tab */
  fun closeVisible(hover: Boolean, focused: Boolean, closable: Boolean): Boolean = closable && (hover || focused)

  /** The tab that takes the focus when [closedId] goes: the left neighbour, as for the chat itself, or the right one for the first tab */
  fun neighbourAfterClose(ids: List<String>, closedId: String): String? {
    val index = ids.indexOf(closedId)
    if (index < 0) return null
    return ids.getOrNull(index - 1) ?: ids.getOrNull(index + 1)
  }
}
