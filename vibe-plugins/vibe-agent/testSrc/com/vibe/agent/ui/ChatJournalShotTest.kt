// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBCheckBox
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.journal.ChatJournal
import com.vibe.agent.journal.ChatJournalService
import com.vibe.agent.journal.JournalCategory
import com.vibe.agent.journal.JournalInput
import com.vibe.agent.journal.JournalLevel
import com.vibe.agent.journal.JournalSummary
import com.vibe.agent.journal.SettingsPage
import com.vibe.agent.ui.composer.PillButton
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The journal panel and its header icon, drawn into PNG files so that they can be looked at,
 * and checked for what a picture cannot say: the unread stripe, the filter, the way back to the settings
 *
 * The pictures are written only when `VIBE_SHOTS` names a folder; the checks always run
 */
class ChatJournalShotTest {
  private class Palette(
    val name: String, val panel: Color, val deep: Color, val text: Color, val dim: Color,
    val accent: Color, val sel: Color, val warn: Color, val border: Color,
  )

  private val neon = Palette(
    "neon", Color(0x1E, 0x1F, 0x24), Color(0x16, 0x17, 0x1B), Color(0xE6, 0xE8, 0xEF), Color(0x00, 0xb8, 0xcc),
    Color(0x00, 0xE5, 0xFF), Color(0x28, 0x32, 0x4A), Color(0xE0, 0xA0, 0x30), Color(0x2A, 0x2D, 0x36))
  private val porcelain = Palette(
    "porcelain", Color(0xf1, 0xee, 0xe9), Color(0xfd, 0xfc, 0xfa), Color(0x2b, 0x2f, 0x33), Color(0x5b, 0x70, 0x7f),
    Color(0x2f, 0x6f, 0x9e), Color(0xdc, 0xe6, 0xee), Color(0x9a, 0x6b, 0x12), Color(0xd9, 0xd4, 0xcc))

  private val tokens = listOf(
    "Panel.background", "Label.foreground", "Component.borderColor", "Borders.color", "Vibe.Chat.metaForeground",
    "Vibe.Journal.warning", "Vibe.Journal.warningForeground", "Vibe.Journal.badgeBackground", "Vibe.Journal.badgeForeground",
    "Vibe.Journal.unreadStripe", "Vibe.Journal.chipBackground", "Vibe.Journal.chipForeground",
  )

  private fun wear(palette: Palette) {
    tokens.forEach { UIManager.put(it, null) }
    UIManager.put("Panel.background", palette.panel)
    UIManager.put("Label.foreground", palette.text)
    UIManager.put("Component.borderColor", palette.border)
    UIManager.put("Borders.color", palette.border)
    UIManager.put("Vibe.Chat.metaForeground", palette.dim)
    UIManager.put("Vibe.Journal.warning", palette.warn)
    UIManager.put("Vibe.Journal.warningForeground", palette.deep)
    UIManager.put("Vibe.Journal.badgeBackground", palette.accent)
    UIManager.put("Vibe.Journal.badgeForeground", palette.deep)
    UIManager.put("Vibe.Journal.unreadStripe", palette.accent)
    UIManager.put("Vibe.Journal.chipBackground", palette.sel)
    UIManager.put("Vibe.Journal.chipForeground", palette.text)
  }

  private fun clear() = tokens.forEach { UIManager.put(it, null) }

  private fun invalidateAll(component: Component) {
    component.invalidate()
    if (component is Container) component.components.forEach { invalidateAll(it) }
  }

  private fun layOut(component: Component) {
    if (component is Container) {
      component.doLayout()
      component.components.forEach { layOut(it) }
    }
  }

  private fun <T : Component> find(root: Component, type: Class<T>): List<T> {
    val found = mutableListOf<T>()
    fun walk(c: Component) {
      if (type.isInstance(c)) found += type.cast(c)
      if (c is Container) c.components.forEach { walk(it) }
    }
    walk(root)
    return found
  }

  private fun rows(panel: Component): Int = find(panel, JTextArea::class.java).size

  /** A journal with something of every kind, the older half already read */
  private fun seeded(): ChatJournalService {
    val now = 1_760_445_600_000L
    var entries = emptyList<com.vibe.agent.journal.JournalEntry>()
    fun add(minutesAgo: Int, input: JournalInput) {
      entries = ChatJournal.append(entries, input, now - minutesAgo * 60_000L, 200)
    }
    add(95, JournalInput(JournalCategory.AGENTS, JournalLevel.INFO, t("chat.greeting.ready", "agents" to "claude-code, codex", "providers" to "OpenRouter, Together")))
    add(94, JournalInput(JournalCategory.KEYS, JournalLevel.INFO, t("chat.greeting.keys", "acp" to "~/.jetbrains/acp.json"), SettingsPage.PROVIDERS))
    entries = ChatJournal.markAllRead(entries)
    add(40, JournalInput(JournalCategory.PROVIDERS, JournalLevel.WARNING, t("providers.dormant", "names" to "OpenRouter, Together"), SettingsPage.MODELS))
    add(31, JournalInput(JournalCategory.HOOKS, JournalLevel.WARNING, t("chat.hooksDisabled"), SettingsPage.AGENT))
    repeat(3) {
      add(12 - it * 4, JournalInput(JournalCategory.PROVIDERS, JournalLevel.WARNING, t("cache.cold", "minutes" to 7, "pays" to "5"), null))
    }
    add(6, JournalInput(JournalCategory.CHECKS, JournalLevel.WARNING, t("chat.checks.notify", "items" to
      (1..9).joinToString("; ") { "possible secret: src/main/kotlin/com/example/service/Config$it.kt" })))
    add(2, JournalInput(JournalCategory.CHECKS, JournalLevel.INFO, t("chat.breakerCleared", "count" to 1)))
    return ChatJournalService().also {
      it.limitSource = { 200 }
      it.loadState(ChatJournalService.State().apply { this.entries = ChatJournal.serialize(entries) })
    }
  }

  /** Swing belongs to the event thread, and the panel redraws from a timer there: a test touches it from nowhere else */
  private fun <T> onEdt(block: () -> T): T {
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(block) }
    return result!!.getOrThrow()
  }

  private val parents = mutableListOf<Disposable>()

  @AfterEach
  fun tearDown() {
    onEdt { parents.forEach { Disposer.dispose(it) } }
    parents.clear()
    clear()
  }

  private fun panelOf(
    journal: ChatJournalService, opened: MutableList<SettingsPage>, palette: Palette, width: Int = 460, height: Int = 440,
  ): JPanel = onEdt {
    val parent = Disposer.newDisposable()
    parents += parent
    val panel = ChatJournalPanel(journal, { opened += it }, parent)
    panel.preferredSize = Dimension(width, height)
    JPanel(BorderLayout()).apply {
      background = palette.panel
      add(panel, BorderLayout.CENTER)
      setSize(width, height)
    }
  }

  private fun settle(host: JPanel) = onEdt {
    repeat(4) {
      invalidateAll(host)
      layOut(host)
    }
  }

  /** Waits for the timer of the panel and for what it posted to the event thread */
  private fun letTheRedrawHappen() {
    Thread.sleep(REDRAW_WAIT_MS)
    onEdt { }
  }

  private fun click(button: Component) = onEdt {
    button.dispatchEvent(MouseEvent(button, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 1, 1, 1, false, MouseEvent.BUTTON1))
  }

  private fun buttons(host: Component, text: String) = find(host, PillButton::class.java).filter { it.text == text }

  private fun save(out: File, name: String, host: JPanel) {
    settle(host)
    Thread.sleep(200)
    val image = BufferedImage(host.width, host.height, BufferedImage.TYPE_INT_RGB)
    onEdt {
      val g = image.createGraphics()
      host.printAll(g)
      g.dispose()
    }
    out.mkdirs()
    ImageIO.write(image, "png", File(out, "$name.png"))
  }

  @Test
  fun `opening the panel reads everything, and the entries that were unread keep their mark`() {
    wear(neon)
    val journal = seeded()
    assertEquals(JournalSummary(total = 7, unread = 5, hasUnreadWarning = true), journal.summary())
    val host = panelOf(journal, mutableListOf(), neon)
    assertEquals(JournalSummary(total = 7, unread = 0, hasUnreadWarning = false), journal.summary())
    assertEquals(7, rows(host))
  }

  @Test
  fun `the filter keeps only warnings`() {
    wear(neon)
    val journal = seeded()
    val host = panelOf(journal, mutableListOf(), neon)
    settle(host)
    val all = rows(host)
    assertEquals(journal.entries.size, all)
    onEdt { find(host, JBCheckBox::class.java).single().doClick(0) }
    val warnings = journal.entries.count { it.level == JournalLevel.WARNING }
    assertEquals(warnings, rows(host))
    assertTrue(warnings in 1 until all)
  }

  @Test
  fun `the category filter keeps one category and offers only the categories that have entries`() {
    wear(neon)
    val journal = seeded()
    val host = panelOf(journal, mutableListOf(), neon)
    val combo = find(host, ComboBox::class.java).single()
    assertEquals(6, combo.itemCount)
    onEdt { combo.selectedIndex = 1 }
    assertEquals(journal.entries.count { it.category == JournalCategory.PROVIDERS }, rows(host))
  }

  @Test
  fun `a long entry is cut and the button shows it in full, and a setting button names its page`() {
    wear(neon)
    val journal = seeded()
    val opened = mutableListOf<SettingsPage>()
    val host = panelOf(journal, opened, neon)
    val longest = journal.entries.maxByOrNull { it.text.length }!!
    val shownBefore = find(host, JTextArea::class.java).map { it.text }
    assertTrue(shownBefore.none { it == longest.text })
    val showFull = buttons(host, t("journal.showFull")).single()
    click(showFull)
    assertTrue(find(host, JTextArea::class.java).any { it.text == longest.text })
    assertEquals(t("journal.showLess"), showFull.text)
    val settings = buttons(host, t("journal.openSetting"))
    assertEquals(journal.entries.count { it.page != null }, settings.size)
    settings.forEach { click(it) }
    assertEquals(journal.entries.filter { it.page != null }.map { it.page }.reversed().toSet(), opened.toSet())
  }

  @Test
  fun `the clear button empties the journal and the panel says so`() {
    wear(neon)
    val journal = seeded()
    val host = panelOf(journal, mutableListOf(), neon)
    click(buttons(host, t("journal.clear")).single())
    letTheRedrawHappen()
    assertTrue(journal.entries.isEmpty())
    assertEquals(0, rows(host))
    assertTrue(find(host, JLabel::class.java).any { it.text == t("journal.empty") })
  }

  @Test
  fun `a line written while the panel is open shows up and is read at once`() {
    wear(neon)
    val journal = seeded()
    val host = panelOf(journal, mutableListOf(), neon)
    journal.append(JournalInput(JournalCategory.MISC, JournalLevel.WARNING, "a late line"))
    letTheRedrawHappen()
    assertEquals(0, journal.summary().unread)
    assertEquals(journal.entries.size, rows(host))
    assertTrue(find(host, JTextArea::class.java).any { it.text == "a late line" })
  }

  @Test
  fun `the icon says nothing when all is read, counts the unread and takes the warning colour`() {
    wear(neon)
    val glyph = AllIcons.Actions.ListFiles
    val calm = JournalBadgeIcon(glyph, JournalSummary(total = 3, unread = 0, hasUnreadWarning = false))
    val unread = JournalBadgeIcon(glyph, JournalSummary(total = 3, unread = 4, hasUnreadWarning = false))
    val many = JournalBadgeIcon(glyph, JournalSummary(total = 300, unread = 250, hasUnreadWarning = true))
    assertEquals(glyph.iconWidth, calm.iconWidth)
    assertTrue(unread.iconWidth > glyph.iconWidth)
    assertEquals(glyph.iconHeight, unread.iconHeight)
    assertEquals(unread.iconWidth, many.iconWidth)
  }

  @Test
  fun `shots of the journal`() {
    val folder = System.getenv("VIBE_SHOTS") ?: return
    val out = File(folder)
    for (palette in listOf(neon, porcelain)) {
      wear(palette)
      val empty = ChatJournalService().also { it.limitSource = { 200 } }
      save(out, "journal-${palette.name}-empty", panelOf(empty, mutableListOf(), palette))
      save(out, "journal-${palette.name}-warnings", panelOf(seeded(), mutableListOf(), palette))
      val filtered = panelOf(seeded(), mutableListOf(), palette)
      onEdt { find(filtered, JBCheckBox::class.java).single().doClick(0) }
      save(out, "journal-${palette.name}-only-warnings", filtered)
      val opened = panelOf(seeded(), mutableListOf(), palette)
      click(buttons(opened, t("journal.showFull")).first())
      save(out, "journal-${palette.name}-expanded", opened)
      save(out, "journal-${palette.name}-narrow", panelOf(seeded(), mutableListOf(), palette, width = 340, height = 440))
      saveIcons(out, palette)
    }
  }

  private fun saveIcons(out: File, palette: Palette) {
    val glyph = AllIcons.Actions.ListFiles
    val states = listOf(
      JournalSummary(total = 4, unread = 0, hasUnreadWarning = false),
      JournalSummary(total = 4, unread = 3, hasUnreadWarning = false),
      JournalSummary(total = 120, unread = 12, hasUnreadWarning = true),
      JournalSummary(total = 300, unread = 250, hasUnreadWarning = true),
    )
    val scale = 6
    val cell = 32
    val image = BufferedImage(cell * states.size, cell, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = palette.panel
    g.fillRect(0, 0, image.width, image.height)
    val host = JLabel()
    states.forEachIndexed { i, summary -> JournalBadgeIcon(glyph, summary).paintIcon(host, g, i * cell + 6, 8) }
    g.dispose()
    val big = BufferedImage(image.width * scale, image.height * scale, BufferedImage.TYPE_INT_RGB)
    val bg = big.createGraphics()
    bg.drawImage(image.getScaledInstance(big.width, big.height, java.awt.Image.SCALE_REPLICATE), 0, 0, null)
    bg.dispose()
    out.mkdirs()
    ImageIO.write(big, "png", File(out, "journal-${palette.name}-icons.png"))
  }

  private companion object {
    /** A little over the delay of the panel's own timer */
    const val REDRAW_WAIT_MS = 400L
  }
}
