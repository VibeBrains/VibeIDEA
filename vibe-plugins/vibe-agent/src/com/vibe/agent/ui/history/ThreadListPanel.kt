// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.history

import com.vibe.agent.i18n.VibeI18n.t
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.util.Alarm
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.vibe.agent.history.ChatThread
import com.vibe.agent.history.TranscriptSearch
import com.vibe.agent.history.VibeChatHistory
import com.vibe.agent.ui.composer.PillButton
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.LocalDate
import java.time.ZoneId
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingConstants

/**
 * The one thread-list component behind all three history surfaces (VibeIDE port):
 * the landing block under the composer, the history popup and the right rail.
 * Data comes from [VibeChatHistory]; the panel re-renders on every history mutation,
 * on scope change and on a debounced search query. Rows are plain Swing components
 * (hover actions need real children), stacked vertically; the panel tracks the
 * viewport width so it never scrolls horizontally.
 */
class ThreadListPanel(
  private val project: Project,
  private val mode: Mode,
  parentDisposable: Disposable,
  private val callbacks: Callbacks,
) : JPanel(), Disposable, Scrollable {
  enum class Mode { LANDING, POPUP, RAIL }

  interface Callbacks {
    fun onOpen(threadId: String)

    fun onOpenAtMessage(threadId: String, messageIndex: Int)

    /** POPUP closes itself after opening a thread. */
    fun onAfterOpen() {}
  }

  private val searchAlarm: Alarm
  private var query = ""
  private var pendingQuery = ""
  private var landingExpanded = false

  /** RAIL: the highlighted row; re-renders on change. */
  var currentThreadId: String? = null
    set(value) {
      if (field == value) return
      field = value
      render()
    }

  init {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
    alignmentX = Component.LEFT_ALIGNMENT
    Disposer.register(parentDisposable, this)
    searchAlarm = Alarm(this)
    VibeChatHistory.getInstance().addListener(this, Runnable { render() })
    render()
  }

  override fun dispose() {}

  /** Re-queries [VibeChatHistory] and rebuilds the rows. */
  fun refresh() {
    render()
  }

  /** POPUP/RAIL: the owner feeds the query; re-render after [SEARCH_DEBOUNCE_MS]. */
  fun setSearchQuery(query: String) {
    pendingQuery = query
    searchAlarm.cancelAllRequests()
    searchAlarm.addRequest({
      if (this.query != pendingQuery) {
        this.query = pendingQuery
        render()
      }
    }, SEARCH_DEBOUNCE_MS)
  }

  private fun render() {
    removeAll()
    val history = VibeChatHistory.getInstance()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val workspaceId = project.basePath
    val listed = history.listed()
    val scoped = if (history.showAllProjects) listed else listed.filter { history.matchesWorkspace(it, workspaceId) }

    if (history.otherProjectsCount(workspaceId) > 0) {
      add(buildScopeToggle(history))
    }

    val trimmed = query.trim()
    val searching = trimmed.isNotEmpty()
    val matches = if (searching) TranscriptSearch.search(trimmed, scoped) else emptyMap()
    val visible = if (searching) scoped.filter { matches.containsKey(it.id) } else scoped

    if (searching && !history.showAllProjects) {
      val outOfScope = listed.filter { !history.matchesWorkspace(it, workspaceId) }
      val extra = TranscriptSearch.search(trimmed, outOfScope).size
      if (extra > 0) {
        add(linkLine(t("history.moreInOther", "count" to extra)) { history.showAllProjects = true })
      }
    }

    when {
      visible.isEmpty() -> renderEmptyState(searching, trimmed)
      mode == Mode.LANDING -> renderLanding(history, visible, matches, today, zone)
      mode == Mode.RAIL && !searching -> renderGrouped(history, visible, matches, today, zone)
      else -> visible.forEach { add(makeRow(history, it, matches[it.id], today, zone)) }
    }

    revalidate()
    repaint()
  }

  private fun renderEmptyState(searching: Boolean, trimmedQuery: String) {
    if (mode == Mode.LANDING) return // the landing owner hides the whole block when there is nothing to show
    val text = when {
      searching && mode == Mode.RAIL -> t("history.noMatchesFor", "query" to trimmedQuery)
      searching -> t("history.noMatches")
      else -> t("history.empty")
    }
    add(mutedLine(text))
  }

  private fun renderLanding(
    history: VibeChatHistory,
    visible: List<ChatThread>,
    matches: Map<String, TranscriptSearch.Match>,
    today: LocalDate,
    zone: ZoneId,
  ) {
    val shown = if (landingExpanded) visible else visible.take(LANDING_LIMIT)
    shown.forEach { add(makeRow(history, it, matches[it.id], today, zone)) }
    if (visible.size > LANDING_LIMIT) {
      val text = if (landingExpanded) t("history.collapse") else t("history.more", "count" to (visible.size - LANDING_LIMIT))
      add(expanderLine(text) {
        landingExpanded = !landingExpanded
        render()
      })
    }
  }

  private fun renderGrouped(
    history: VibeChatHistory,
    visible: List<ChatThread>,
    matches: Map<String, TranscriptSearch.Match>,
    today: LocalDate,
    zone: ZoneId,
  ) {
    for (group in HistoryDates.Group.entries) {
      val inGroup = visible.filter { groupOf(it, today, zone) == group }
      if (inGroup.isEmpty()) continue
      add(groupHeader(group.title))
      inGroup.forEach { add(makeRow(history, it, matches[it.id], today, zone)) }
    }
  }

  private fun groupOf(thread: ChatThread, today: LocalDate, zone: ZoneId): HistoryDates.Group {
    val date = HistoryDates.localDate(thread.lastModified, zone) ?: return HistoryDates.Group.OLDER
    return HistoryDates.groupOf(date, today)
  }

  private fun makeRow(
    history: VibeChatHistory,
    thread: ChatThread,
    match: TranscriptSearch.Match?,
    today: LocalDate,
    zone: ZoneId,
  ): ThreadRow {
    val workspaceId = project.basePath
    // «Все проекты» only: a thread from another workspace gets a badge and the move action.
    val foreign = history.showAllProjects && !history.matchesWorkspace(thread, workspaceId)
    val date = HistoryDates.localDate(thread.lastModified, zone)
    val badge = date?.let { "${thread.dialogueCount} · ${HistoryDates.badgeLabel(it, today)}" } ?: "${thread.dialogueCount}"
    val quote = match?.quote
    return ThreadRow(
      title = thread.title,
      quote = quote,
      badgeText = badge,
      projectBadge = if (foreign) thread.workspaceLabel ?: (if (thread.workspaceId != null) t("history.otherProject") else t("history.noProject")) else null,
      showMoveHere = foreign,
      isCurrent = mode == Mode.RAIL && thread.id == currentThreadId,
      onOpen = {
        callbacks.onOpen(thread.id)
        callbacks.onAfterOpen()
      },
      onOpenQuote = {
        quote?.let {
          callbacks.onOpenAtMessage(thread.id, it.messageIndex)
          callbacks.onAfterOpen()
        }
      },
      onMoveHere = { history.reassign(thread.id, project.basePath, project.name) },
      onDuplicate = { history.duplicate(thread.id) },
      onDelete = { history.delete(thread.id) },
    ).apply { alignmentX = Component.LEFT_ALIGNMENT }
  }

  private fun buildScopeToggle(history: VibeChatHistory): JComponent {
    val all = history.showAllProjects
    val otherCount = history.otherProjectsCount(project.basePath)
    val tooltip = t("history.otherCount", "count" to otherCount)
    return JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(SCOPE_SEGMENT_GAP), 0)).apply {
      isOpaque = false
      alignmentX = Component.LEFT_ALIGNMENT
      border = JBUI.Borders.empty(SCOPE_PAD_V, SCOPE_PAD_H)
      add(ScopeSegment(t("history.scope.this"), active = !all, countText = null, tooltip = null) { history.showAllProjects = false })
      // The invite tooltip only makes sense while the other projects are still hidden.
      add(ScopeSegment(
        t("history.scope.all"),
        active = all,
        countText = if (!all) "+$otherCount" else null,
        tooltip = if (!all) tooltip else null,
      ) { history.showAllProjects = true })
      // FlowLayout panels report an unbounded maximum: cap the height so BoxLayout never stretches the toggle.
      maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }
  }

  private fun groupHeader(title: String): JLabel = JLabel(title.uppercase()).apply {
    font = JBFont.label().deriveFont(GROUP_FONT_SIZE).asBold()
    foreground = GROUP_FG
    border = JBUI.Borders.empty(GROUP_TOP_PAD, LINE_SIDE_PAD, GROUP_BOTTOM_PAD, LINE_SIDE_PAD)
    alignmentX = Component.LEFT_ALIGNMENT
  }

  private fun mutedLine(text: String): JLabel = JLabel(text).apply {
    font = JBFont.label().deriveFont(LINE_FONT_SIZE)
    foreground = GROUP_FG
    border = JBUI.Borders.empty(LINE_PAD_V, LINE_SIDE_PAD)
    alignmentX = Component.LEFT_ALIGNMENT
  }

  private fun expanderLine(text: String, onClick: () -> Unit): PillButton =
    PillButton(text = text) { onClick() }.apply {
      foreground = GROUP_FG
      horizontalAlignment = SwingConstants.LEFT
      alignmentX = Component.LEFT_ALIGNMENT
      maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

  private fun linkLine(text: String, onClick: () -> Unit): PillButton =
    PillButton(text = text) { onClick() }.apply {
      foreground = LINK_FG
      horizontalAlignment = SwingConstants.LEFT
      alignmentX = Component.LEFT_ALIGNMENT
      maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

  // Scrollable: track the viewport width so long titles truncate instead of forcing a horizontal scrollbar.
  override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

  override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(SCROLL_UNIT)

  override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = visibleRect.height

  override fun getScrollableTracksViewportWidth(): Boolean = true

  override fun getScrollableTracksViewportHeight(): Boolean = false

  /**
   * One half of the segmented scope pill: rounded fill while active, muted text while
   * not; the «Все проекты» segment can carry the «+N» mini-pill. Tooltips register the
   * children with ToolTipManager (which swallows mouse events), so the click listener
   * goes on every child too.
   */
  private class ScopeSegment(
    text: String,
    private val active: Boolean,
    countText: String?,
    tooltip: String?,
    onClick: () -> Unit,
  ) : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(SEGMENT_INNER_GAP), 0)) {
    init {
      isOpaque = false
      border = JBUI.Borders.empty(SEGMENT_PAD_V, SEGMENT_PAD_H)
      cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
      toolTipText = tooltip
      val clicker = object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
          if (e.button == MouseEvent.BUTTON1) onClick()
        }
      }
      addMouseListener(clicker)
      add(JLabel(text).apply {
        font = JBFont.label().deriveFont(SCOPE_FONT_SIZE)
        foreground = if (active) PillButton.PILL_FG else GROUP_FG
        toolTipText = tooltip
        addMouseListener(clicker)
      })
      countText?.let {
        add(HistoryPill(it).apply {
          toolTipText = tooltip
          addMouseListener(clicker)
        })
      }
    }

    override fun paintComponent(g: Graphics) {
      if (active) {
        val g2 = g.create() as Graphics2D
        try {
          g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
          g2.color = HistoryPill.BG
          g2.fillRoundRect(0, 0, width, height, height, height)
        }
        finally {
          g2.dispose()
        }
      }
      super.paintComponent(g)
    }
  }

  companion object {
    /** Landing shows this many rows before the «Ещё N…» expander. */
    const val LANDING_LIMIT = 3
    private const val SEARCH_DEBOUNCE_MS = 150
    private const val SCROLL_UNIT = 16
    private const val SCOPE_FONT_SIZE = 10f
    private const val GROUP_FONT_SIZE = 10f
    private const val LINE_FONT_SIZE = 12f
    private const val SCOPE_SEGMENT_GAP = 4
    private const val SCOPE_PAD_V = 2
    private const val SCOPE_PAD_H = 8
    private const val SEGMENT_INNER_GAP = 4
    private const val SEGMENT_PAD_V = 2
    private const val SEGMENT_PAD_H = 8
    private const val GROUP_TOP_PAD = 10
    private const val GROUP_BOTTOM_PAD = 2
    private const val LINE_PAD_V = 6
    private const val LINE_SIDE_PAD = 12

    internal val GROUP_FG: Color = JBColor.namedColor("Vibe.History.groupForeground", JBColor.namedColor("Label.infoForeground", JBColor.GRAY))
    private val LINK_FG: Color = JBUI.CurrentTheme.Link.Foreground.ENABLED
  }
}
