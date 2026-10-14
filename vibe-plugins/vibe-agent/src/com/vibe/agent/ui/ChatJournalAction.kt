// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.journal.ChatJournalService
import com.vibe.agent.journal.JournalSummary
import com.vibe.agent.settings.VibeSettingsPages
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import javax.swing.Icon

/**
 * The journal button in the header of the agent window
 *
 * The icon follows the service: a number of unread entries over the glyph, and the colour of a warning while one of them is a warning
 * The toolbar asks for the icon on every update, so nothing is rebuilt when the counter moves
 */
class ChatJournalAction(private val project: Project) : DumbAwareAction() {
  private var popup: JBPopup? = null

  /** The icon is made again only when the counters change: a new object on every update would repaint the toolbar for nothing */
  private var shown: Pair<JournalSummary, Icon>? = null

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    val summary = ChatJournalService.getInstance(project).summary()
    val icon = shown?.takeIf { it.first == summary }?.second
               ?: JournalBadgeIcon(AllIcons.Actions.ListFiles, summary).also { shown = summary to it }
    e.presentation.icon = icon
    e.presentation.text = t("journal.title")
    e.presentation.description = if (summary.unread > 0) t("journal.tooltipUnread", "count" to summary.unread) else t("journal.tooltip")
  }

  override fun actionPerformed(e: AnActionEvent) {
    val open = popup
    if (open != null && open.isVisible) {
      open.cancel()
      return
    }
    popup = ChatJournalPopup.show(project, e.inputEvent?.component)
  }
}

/** Opens the journal over the chat, under the header of the agent window and against its right edge */
internal object ChatJournalPopup {
  fun show(project: Project, fallbackAnchor: Component?): JBPopup {
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(VibeToolWindows.AGENT)
    val host = toolWindow?.component?.takeIf { it.isShowing } ?: fallbackAnchor?.takeIf { it.isShowing }
    val disposable = Disposer.newDisposable("ChatJournalPopup")
    lateinit var popup: JBPopup
    val panel = ChatJournalPanel(ChatJournalService.getInstance(project), { page ->
      popup.cancel()
      VibeSettingsPages.open(project, page)
    }, disposable)
    if (host != null) panel.preferredSize = fitted(panel.preferredSize, host)
    popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, panel.focusTarget)
      .setRequestFocus(true)
      .setFocusable(true)
      .setResizable(true)
      .setMovable(false)
      .setCancelOnClickOutside(true)
      .setTitle(null)
      .addListener(object : JBPopupListener {
        override fun onClosed(event: LightweightWindowEvent) = Disposer.dispose(disposable)
      })
      .createPopup()
    if (host != null) popup.show(RelativePoint(host, Point(host.width - panel.preferredSize.width - JBUI.scale(EDGE_GAP), 0)))
    else popup.showInFocusCenter()
    return popup
  }

  /** The panel never wider or taller than the window it opens over: it is a layer over the chat, not a second window */
  private fun fitted(wanted: Dimension, host: Component): Dimension =
    Dimension(minOf(wanted.width, host.width - 2 * JBUI.scale(EDGE_GAP)), minOf(wanted.height, host.height - 2 * JBUI.scale(EDGE_GAP)))
      .let { if (it.width > 0 && it.height > 0) it else wanted }

  private const val EDGE_GAP = 6
}

/**
 * The glyph of the journal with the number of unread entries in a pill at its upper right corner
 *
 * No unread entries: the plain glyph. An unread warning paints both the glyph and the pill in the warning colour
 */
internal class JournalBadgeIcon(private val glyph: Icon, summary: JournalSummary) : Icon {
  private val unread = summary.unread
  private val warning = summary.hasUnreadWarning
  private val shown: Icon = if (warning) IconUtil.colorize(glyph, JournalTheme.WARNING) else glyph
  private val label: String = if (unread > MAX_SHOWN) "$MAX_SHOWN+" else unread.toString()

  override fun getIconWidth(): Int = glyph.iconWidth + if (unread > 0) JBUI.scale(OVERHANG) else 0
  override fun getIconHeight(): Int = glyph.iconHeight

  override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
    shown.paintIcon(c, g, x, y + JBUI.scale(GLYPH_DROP))
    if (unread == 0) return
    val g2 = g.create() as Graphics2D
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
      g2.font = JBFont.small().deriveFont(Font.BOLD, BADGE_PT)
      val metrics = g2.fontMetrics
      val height = JBUI.scale(BADGE_HEIGHT)
      val width = maxOf(height, metrics.stringWidth(label) + JBUI.scale(BADGE_PAD) * 2)
      val left = x + iconWidth - width
      g2.color = if (warning) JournalTheme.WARNING else JournalTheme.BADGE_BACKGROUND
      g2.fillRoundRect(left, y, width, height, height, height)
      g2.color = if (warning) JournalTheme.WARNING_FOREGROUND else JournalTheme.BADGE_FOREGROUND
      g2.drawString(label, left + (width - metrics.stringWidth(label)) / 2, y + (height + metrics.ascent - metrics.descent) / 2)
    }
    finally {
      g2.dispose()
    }
  }

  private companion object {
    /** Past this the pill says `99+`: a count nobody reads digit by digit, and a pill that stays small */
    const val MAX_SHOWN = 99
    const val BADGE_PT = 9f
    const val BADGE_HEIGHT = 11
    const val BADGE_PAD = 3

    /** The pill sticks out to the right of the glyph by this much, so it hides less of the glyph */
    const val OVERHANG = 6

    /** The glyph sits a little lower so that the pill at the top does not cover its upper edge */
    const val GLYPH_DROP = 1
  }
}
