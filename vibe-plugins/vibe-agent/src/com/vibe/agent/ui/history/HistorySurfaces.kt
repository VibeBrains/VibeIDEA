// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.history

import com.vibe.agent.i18n.VibeI18n.t

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent

/**
 * History dropdown of the composer (VibeIDE §2.2): a focused popup with a search field
 * («Фильтр…», autofocus), the scope toggle inside the list panel, and the flat thread list.
 * Opening a thread closes the popup; closing resets the filter by construction (the popup
 * and its panel are built per opening).
 */
object HistoryPopup {
  private const val WIDTH = 340
  private const val HEIGHT = 400

  fun show(project: Project, anchor: JComponent, parentDisposable: Disposable, open: (threadId: String) -> Unit, openAt: (threadId: String, messageIndex: Int) -> Unit) {
    var popup: JBPopup? = null
    val panelDisposable = Disposer.newDisposable("vibe-history-popup")
    Disposer.register(parentDisposable, panelDisposable)
    val list = ThreadListPanel(project, ThreadListPanel.Mode.POPUP, panelDisposable, object : ThreadListPanel.Callbacks {
      override fun onOpen(threadId: String) = open(threadId)
      override fun onOpenAtMessage(threadId: String, messageIndex: Int) = openAt(threadId, messageIndex)
      override fun onAfterOpen() { popup?.cancel() }
    })
    val search = JBTextField().apply {
      emptyText.text = t("history.filter")
      document.addDocumentListener(object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) = list.setSearchQuery(text)
      })
    }
    val content = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
      border = JBUI.Borders.empty(8)
      preferredSize = Dimension(JBUI.scale(WIDTH), JBUI.scale(HEIGHT))
      add(search, BorderLayout.NORTH)
      add(com.vibe.agent.ui.VibeScroll.pane(JPanel(BorderLayout()).apply {
        isOpaque = false
        add(list, BorderLayout.NORTH)
      }).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
      }, BorderLayout.CENTER)
    }
    popup = JBPopupFactory.getInstance()
      .createComponentPopupBuilder(content, search)
      .setRequestFocus(true)
      .setResizable(true)
      .setCancelOnClickOutside(true)
      .createPopup()
      .also { com.vibe.agent.ui.VibeScroll.thinAllIn(it.content) }
    popup.addListener(object : com.intellij.openapi.ui.popup.JBPopupListener {
      override fun onClosed(event: com.intellij.openapi.ui.popup.LightweightWindowEvent) = Disposer.dispose(panelDisposable)
    })
    popup.showUnderneathOf(anchor)
  }
}

/**
 * Right-hand history rail (VibeIDE §2.3): fixed 280px, «ИСТОРИЯ» header with a collapse
 * button, a search field («Поиск») and the date-grouped list. Collapsed state persists
 * app-wide; default collapsed.
 */
class HistoryRail(
  project: Project,
  parentDisposable: Disposable,
  callbacks: ThreadListPanel.Callbacks,
  private val onCollapse: () -> Unit,
) : JPanel(BorderLayout(0, JBUI.scale(GAP))) {
  private val list = ThreadListPanel(project, ThreadListPanel.Mode.RAIL, parentDisposable, callbacks)
  private val search = JBTextField().apply {
    emptyText.text = t("history.search")
    document.addDocumentListener(object : DocumentAdapter() {
      override fun textChanged(e: DocumentEvent) = list.setSearchQuery(text)
    })
  }

  var currentThreadId: String?
    get() = list.currentThreadId
    set(value) { list.currentThreadId = value }

  init {
    isOpaque = false
    preferredSize = Dimension(JBUI.scale(WIDTH), 0)
    border = JBUI.Borders.compound(JBUI.Borders.customLineLeft(BORDER), JBUI.Borders.empty(PAD))
    val header = JPanel(BorderLayout()).apply {
      isOpaque = false
      add(JLabel(TITLE).apply {
        font = JBFont.label().deriveFont(TITLE_FONT_SIZE).asBold()
        foreground = TITLE_FG
      }, BorderLayout.WEST)
      add(JLabel(AllIcons.Actions.Close).apply {
        toolTipText = t("tabs.collapseHistory")
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
          override fun mouseClicked(e: MouseEvent) { onCollapse() }
        })
      }, BorderLayout.EAST)
    }
    val top = JPanel(BorderLayout(0, JBUI.scale(GAP))).apply {
      isOpaque = false
      add(header, BorderLayout.NORTH)
      add(search, BorderLayout.CENTER)
    }
    add(top, BorderLayout.NORTH)
    add(com.vibe.agent.ui.VibeScroll.pane(JPanel(BorderLayout()).apply {
      isOpaque = false
      add(list, BorderLayout.NORTH)
    }).apply {
      border = JBUI.Borders.empty()
      horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
      isOpaque = false
      viewport.isOpaque = false
    }, BorderLayout.CENTER)
  }

  fun refresh() = list.refresh()

  companion object {
    /** VibeIDE: rail width 280px, hidden by default. */
    const val WIDTH = 280
    private const val PAD = 8
    private const val GAP = 6
    private val TITLE: String get() = t("history.title")
    private const val TITLE_FONT_SIZE = 10f
    private const val KEY_COLLAPSED = "vibe.history.railCollapsed"

    var collapsed: Boolean
      get() = PropertiesComponent.getInstance().getBoolean(KEY_COLLAPSED, true)
      set(value) = PropertiesComponent.getInstance().setValue(KEY_COLLAPSED, value, true)

    val BORDER = JBColor.namedColor("Vibe.Composer.separator", JBColor.namedColor("Separator.separatorColor", JBColor.border()))
    /** One source of truth for the token — the list panel declares it. */
    val TITLE_FG get() = ThreadListPanel.GROUP_FG
  }
}
