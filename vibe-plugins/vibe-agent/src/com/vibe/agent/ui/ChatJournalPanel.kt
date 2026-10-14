// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.journal.ChatJournal
import com.vibe.agent.journal.ChatJournalService
import com.vibe.agent.journal.JournalCategory
import com.vibe.agent.journal.JournalEntry
import com.vibe.agent.journal.JournalFilter
import com.vibe.agent.journal.JournalLevel
import com.vibe.agent.journal.SettingsPage
import com.vibe.agent.ui.composer.PillButton
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Colours of the journal: the themes name the tokens, a theme that does not gets values from keys every theme has
 */
internal object JournalTheme {
  val WARNING: Color = JBColor.namedColor("Vibe.Journal.warning", JBColor(0xB07600, 0xE0A030))
  val WARNING_FOREGROUND: Color = JBColor.namedColor("Vibe.Journal.warningForeground", JBColor(0xFFFFFF, 0x16171B))
  val BADGE_BACKGROUND: Color = JBColor.namedColor("Vibe.Journal.badgeBackground", JBColor(0x3574F0, 0x3574F0))
  val BADGE_FOREGROUND: Color = JBColor.namedColor("Vibe.Journal.badgeForeground", JBColor(0xFFFFFF, 0x16171B))
  val UNREAD_STRIPE: Color = JBColor.namedColor("Vibe.Journal.unreadStripe", JBColor(0x3574F0, 0x3574F0))
  val CHIP_BACKGROUND: Color = JBColor.namedColor("Vibe.Journal.chipBackground", JBColor(0xE8EAEE, 0x2F3342))
  val CHIP_FOREGROUND: Color = JBColor.namedColor("Vibe.Journal.chipForeground", JBColor(0x2B2B2B, 0xBCBEC4))
}

/** Words and settings pages of the journal, in one place so that a new category or page cannot be forgotten in a corner */
internal object JournalLabels {
  fun category(category: JournalCategory): String = when (category) {
    JournalCategory.PROVIDERS -> t("journal.category.providers")
    JournalCategory.KEYS -> t("journal.category.keys")
    JournalCategory.HOOKS -> t("journal.category.hooks")
    JournalCategory.CONFIG -> t("journal.category.config")
    JournalCategory.AGENTS -> t("journal.category.agents")
    JournalCategory.CHECKS -> t("journal.category.checks")
    JournalCategory.MISC -> t("journal.category.other")
  }

  fun level(level: JournalLevel): String = when (level) {
    JournalLevel.INFO -> t("journal.level.info")
    JournalLevel.WARNING -> t("journal.level.warning")
  }

  fun pageTitle(page: SettingsPage): String = when (page) {
    SettingsPage.PROVIDERS -> t("settings.providers.title")
    SettingsPage.MODELS -> t("settings.models.title")
    SettingsPage.AGENT -> t("settings.agent.title")
    SettingsPage.CHAT -> t("settings.chat.title")
    SettingsPage.INTERFACE -> t("settings.ui.title")
    SettingsPage.APPEARANCE -> t("settings.appearance.title")
  }
}

/**
 * The journal as a panel: the service lines of the IDE, newest first, with a filter and the way to the settings they point to
 *
 * Opening it marks everything read, and the entries that were unread at that moment keep a stripe until the panel is closed
 * While the panel is open, what arrives is read at once
 * The panel redraws from the service, not from the lines it was given, so a line written from any thread shows up
 */
class ChatJournalPanel(
  private val journal: ChatJournalService,
  private val onOpenPage: (SettingsPage) -> Unit,
  parent: Disposable,
) : JPanel(BorderLayout()) {
  private val unreadAtOpen: MutableSet<Long> = journal.entries.filter { !it.read }.map { it.id }.toMutableSet()
  private val expanded = mutableSetOf<Long>()
  private var shownCategories: List<Pair<JournalCategory?, Int>> = emptyList()
  private var filter = JournalFilter()
  private var rebuildingFilters = false
  private var disposed = false

  private val categories = com.intellij.openapi.ui.ComboBox<CategoryItem>().apply {
    isSwingPopup = false
    minimumSize = Dimension(0, minimumSize.height)
  }
  private val warningsOnly = JBCheckBox(t("journal.onlyWarnings"))
  private val clearButton = PillButton(t("journal.clear"), outlined = true) { journal.clear() }
  private val column = Column()
  private val body = JPanel(CardLayout())
  private val emptyText = JBLabel().apply {
    horizontalAlignment = JLabel.CENTER
    foreground = ChatTheme.META_FG
    border = JBUI.Borders.empty(EMPTY_PAD)
  }
  private val scroll = VibeScroll.pane(column).apply {
    border = JBUI.Borders.empty()
    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
  }

  /** A burst of lines redraws the list once */
  private val rebuildTimer = Timer(REBUILD_DELAY_MS) { rebuild() }.apply { isRepeats = false }

  /** The component that takes the focus when the panel opens: the list, so that the arrow keys and Esc work at once */
  val focusTarget: JComponent get() = scroll

  init {
    preferredSize = Dimension(JBUI.scale(PANEL_WIDTH), JBUI.scale(PANEL_HEIGHT))
    border = JBUI.Borders.empty()
    add(header(), BorderLayout.NORTH)
    body.add(scroll, CARD_LIST)
    body.add(emptyText, CARD_EMPTY)
    add(body, BorderLayout.CENTER)
    categories.addActionListener {
      if (rebuildingFilters) return@addActionListener
      filter = filter.copy(category = (categories.selectedItem as? CategoryItem)?.category)
      rebuild()
    }
    warningsOnly.addActionListener {
      filter = filter.copy(onlyWarnings = warningsOnly.isSelected)
      rebuild()
    }
    Disposer.register(parent) {
      disposed = true
      rebuildTimer.stop()
    }
    rebuild()
    // Opening reads everything; the marks of what was unread are already taken, and nothing needs a redraw for it
    journal.markAllRead()
    journal.addListener(parent) {
      SwingUtilities.invokeLater {
        if (disposed) return@invokeLater
        journal.markAllRead()
        rebuildTimer.restart()
      }
    }
  }

  private fun header(): JComponent {
    val title = JBLabel(t("journal.title")).apply { font = JBFont.label().deriveFont(Font.BOLD, TITLE_PT) }
    val top = JPanel(BorderLayout()).apply {
      isOpaque = false
      add(title, BorderLayout.WEST)
      add(clearButton, BorderLayout.EAST)
    }
    val filters = JPanel(BorderLayout(JBUI.scale(GAP), 0)).apply {
      isOpaque = false
      add(categories, BorderLayout.CENTER)
      add(warningsOnly, BorderLayout.EAST)
    }
    return JPanel(BorderLayout(0, JBUI.scale(GAP))).apply {
      border = JBUI.Borders.empty(HEADER_PAD_V, HEADER_PAD_H)
      add(top, BorderLayout.NORTH)
      add(filters, BorderLayout.CENTER)
    }
  }

  private fun rebuild() {
    if (disposed) return
    val all = journal.entries
    // A cleared journal starts its ids over, so the marks of the old ones must not stick to the new entries
    if (all.isEmpty()) {
      unreadAtOpen.clear()
      expanded.clear()
    }
    refreshCategories(all)
    val shown = ChatJournal.filter(all, filter)
    val keepAt = scroll.verticalScrollBar.value
    column.removeAll()
    shown.forEach { column.add(JournalRow(it, it.id in unreadAtOpen, it.id in expanded, ::onToggle, onOpenPage)) }
    (body.layout as CardLayout).show(body, if (shown.isEmpty()) CARD_EMPTY else CARD_LIST)
    emptyText.text = if (all.isEmpty()) t("journal.empty") else t("journal.emptyFiltered")
    column.revalidate()
    column.repaint()
    SwingUtilities.invokeLater { scroll.verticalScrollBar.value = keepAt }
  }

  /** The category list follows the entries: only a category that has some is offered, with its count */
  private fun refreshCategories(all: List<JournalEntry>) {
    val counts = ChatJournal.countByCategory(all)
    if (filter.category != null && counts.none { it.category == filter.category }) filter = filter.copy(category = null)
    rebuildingFilters = true
    try {
      val offered = listOf<Pair<JournalCategory?, Int>>(null to all.size) + counts.map { it.category to it.count }
      // The list is replaced only when it differs: a combo that is choosing must not have its model taken away from under it
      if (offered != shownCategories) {
        shownCategories = offered
        categories.model = DefaultComboBoxModel(offered.map { (category, count) -> CategoryItem(category, count) }.toTypedArray())
      }
      categories.selectedIndex = offered.indexOfFirst { it.first == filter.category }
      categories.isEnabled = all.isNotEmpty()
      warningsOnly.isSelected = filter.onlyWarnings
    }
    finally {
      rebuildingFilters = false
    }
  }

  private fun onToggle(entry: JournalEntry, open: Boolean) {
    if (open) expanded.add(entry.id) else expanded.remove(entry.id)
  }

  private class CategoryItem(val category: JournalCategory?, val count: Int) {
    override fun toString(): String =
      if (category == null) t("journal.filter.all", "count" to count) else t("journal.filter.item", "category" to JournalLabels.category(category), "count" to count)
  }

  /** The list follows the width of the viewport, so that the lines wrap and the panel never scrolls sideways */
  private class Column : JPanel(), Scrollable {
    init {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(SCROLL_UNIT)
    override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int): Int = visible.height
    override fun getScrollableTracksViewportWidth(): Boolean = true
    override fun getScrollableTracksViewportHeight(): Boolean = false
  }

  /** One entry: level, category, time and counter on the first line, the text, then the buttons */
  private class JournalRow(
    private val entry: JournalEntry,
    unread: Boolean,
    private var open: Boolean,
    private val onToggle: (JournalEntry, Boolean) -> Unit,
    private val onOpenPage: (SettingsPage) -> Unit,
  ) : JPanel(BorderLayout(JBUI.scale(STRIPE_GAP), 0)) {
    private val preview = ChatJournal.preview(entry.text)

    /** A row is as tall as its text and no taller: the list must not stretch rows to fill a tall panel */
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    private val text = JTextArea().apply {
      isEditable = false
      isOpaque = false
      lineWrap = true
      wrapStyleWord = true
      font = JBFont.label().deriveFont(Font.PLAIN, TEXT_PT)
      foreground = JBColor.foreground()
      border = JBUI.Borders.empty()
    }
    private val toggle = PillButton(t("journal.showFull"), outlined = true) { flip() }

    init {
      alignmentX = LEFT_ALIGNMENT
      isOpaque = false
      border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0), JBUI.Borders.empty(ROW_PAD_V, 0, ROW_PAD_V, ROW_PAD_H))
      add(stripe(unread), BorderLayout.WEST)
      add(content(), BorderLayout.CENTER)
      renderText()
    }

    /** The stripe is there for every row, drawn or not, so that the text of read and unread rows starts in one place */
    private fun stripe(unread: Boolean): JComponent = JPanel().apply {
      preferredSize = Dimension(JBUI.scale(STRIPE_WIDTH), 0)
      isOpaque = unread
      background = if (entry.level == JournalLevel.WARNING) JournalTheme.WARNING else JournalTheme.UNREAD_STRIPE
    }

    private fun content(): JComponent {
      val rows = JPanel(BorderLayout(0, JBUI.scale(TEXT_GAP))).apply { isOpaque = false }
      rows.add(meta(), BorderLayout.NORTH)
      rows.add(text, BorderLayout.CENTER)
      val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), 0)).apply {
        isOpaque = false
        if (preview.truncated) add(toggle)
        entry.page?.let { page -> add(openSetting(page)) }
      }
      if (buttons.componentCount > 0) rows.add(buttons, BorderLayout.SOUTH)
      return rows
    }

    private fun meta(): JComponent {
      val line = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), 0)).apply { isOpaque = false }
      line.add(JLabel(if (entry.level == JournalLevel.WARNING) AllIcons.General.Warning else AllIcons.General.Information).apply {
        toolTipText = JournalLabels.level(entry.level)
      })
      line.add(Chip(JournalLabels.category(entry.category)))
      line.add(JLabel(clock(entry.at)).apply {
        font = JBFont.label().deriveFont(Font.PLAIN, META_PT)
        foreground = ChatTheme.META_FG
        toolTipText = DATE_TIME.format(Instant.ofEpochMilli(entry.at).atZone(ZoneId.systemDefault()))
      })
      if (entry.count > 1) {
        line.add(JLabel(t("journal.repeat", "count" to entry.count)).apply {
          font = JBFont.label().deriveFont(Font.BOLD, META_PT)
          foreground = ChatTheme.META_FG
        })
      }
      return line
    }

    private fun openSetting(page: SettingsPage) = PillButton(t("journal.openSetting"), outlined = true) { onOpenPage(page) }.apply {
      toolTipText = JournalLabels.pageTitle(page)
    }

    private fun flip() {
      open = !open
      onToggle(entry, open)
      renderText()
      revalidate()
      repaint()
    }

    private fun renderText() {
      text.text = if (open || !preview.truncated) entry.text else preview.text
      text.caretPosition = 0
      toggle.text = if (open) t("journal.showLess") else t("journal.showFull")
    }
  }

  /** The category tag: a small rounded plate in the colours of the theme */
  private class Chip(text: String) : JLabel(text) {
    init {
      font = JBFont.label().deriveFont(Font.PLAIN, META_PT)
      foreground = JournalTheme.CHIP_FOREGROUND
      border = JBUI.Borders.empty(CHIP_PAD_V, CHIP_PAD_H)
    }

    override fun paintComponent(g: Graphics) {
      val g2 = g.create() as Graphics2D
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = JournalTheme.CHIP_BACKGROUND
        g2.fillRoundRect(0, 0, width, height, height, height)
      }
      finally {
        g2.dispose()
      }
      super.paintComponent(g)
    }
  }

  private companion object {
    const val PANEL_WIDTH = 460
    const val PANEL_HEIGHT = 440
    const val REBUILD_DELAY_MS = 120
    const val TITLE_PT = 13f
    const val TEXT_PT = 12f
    const val META_PT = 11f
    const val GAP = 6
    const val TEXT_GAP = 4
    const val HEADER_PAD_V = 8
    const val HEADER_PAD_H = 10
    const val ROW_PAD_V = 8
    const val ROW_PAD_H = 10
    const val STRIPE_WIDTH = 3
    const val STRIPE_GAP = 7
    const val CHIP_PAD_V = 1
    const val CHIP_PAD_H = 7
    const val EMPTY_PAD = 32
    const val SCROLL_UNIT = 16
    const val CARD_LIST = "list"
    const val CARD_EMPTY = "empty"
    val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)

    fun clock(atMillis: Long): String = CLOCK.format(Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()))
  }
}
