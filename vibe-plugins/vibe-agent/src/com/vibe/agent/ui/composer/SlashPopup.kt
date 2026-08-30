// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import com.vibe.agent.i18n.VibeI18n.t

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.ui.popup.PopupChooserBuilder
import com.intellij.openapi.util.Disposer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ScreenUtil
import com.intellij.ui.ScrollingUtil
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import java.awt.Point
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Slash menu of the composer (VibeIDE §4, honest subset): `/` at the very start of the
 * message opens «Команды чата» (`/commit`, `/skill:`), and `/skill:` anywhere opens the
 * skills list from `.vibe/skills/<id>/SKILL.md`. `/watch` and `/shot` wait for their
 * pipelines. Same focus-less skeleton as the `@` menu: the text area keeps focus, keys
 * are component-local shortcuts, picking inserts text.
 */
class SlashPopup(
  private val project: Project,
  private val textArea: JTextArea,
  parentDisposable: Disposable,
) : Disposable {

  private class Row(val insert: String?, val title: String, val detail: String, val toSkills: Boolean = false)

  private val model = CollectionListModel<Row>()
  private val list = com.intellij.ui.components.JBList(model).apply {
    selectionMode = ListSelectionModel.SINGLE_SELECTION
    cellRenderer = RowRenderer()
    setEmptyText("")
  }
  private val header = JBLabel().apply {
    border = JBUI.Borders.empty(HEADER_PAD_V, HEADER_PAD_H, HEADER_PAD_V / 2, HEADER_PAD_H)
    foreground = MentionColors.HINT_FG
  }

  private var popup: JBPopup? = null
  /** Offset of the `/`; -1 while closed. */
  private var anchor = -1
  private var skillsMode = false

  private val documentListener = object : DocumentListener {
    override fun insertUpdate(e: DocumentEvent) = SwingUtilities.invokeLater { sync() }
    override fun removeUpdate(e: DocumentEvent) = SwingUtilities.invokeLater { sync() }
    override fun changedUpdate(e: DocumentEvent) {}
  }

  init {
    textArea.document.addDocumentListener(documentListener)
    list.addMouseListener(object : MouseAdapter() {
      override fun mouseReleased(e: MouseEvent) {
        if (!SwingUtilities.isLeftMouseButton(e)) return
        val index = list.locationToIndex(e.point)
        if (index >= 0 && list.getCellBounds(index, index)?.contains(e.point) == true) {
          e.consume()
          list.selectedIndex = index
          pickSelected()
        }
      }
    })
    Disposer.register(parentDisposable, this)
    installShortcuts()
  }

  val isShowing: Boolean get() = popup != null

  fun close() {
    popup?.cancel()
  }

  override fun dispose() {
    close()
    textArea.document.removeDocumentListener(documentListener)
  }

  // ---- trigger / sync ------------------------------------------------------------------------

  /**
   * Recomputed from the document on every change: `/` at offset 0 → commands; a word
   * `/skill:<tail>` ending at the caret (start or after whitespace) → skills.
   */
  private fun sync() {
    val text = textArea.text
    val caret = textArea.caretPosition.coerceAtMost(text.length)
    val head = text.substring(0, caret)
    val skillsMatch = SKILL_TAIL.find(head)
    val commandMatch = if (head.startsWith(SLASH) && !head.contains(' ') && !head.contains('\n')) 0 else -1
    when {
      skillsMatch != null && (skillsMatch.range.first == 0 || head[skillsMatch.range.first - 1].isWhitespace()) ->
        openOrUpdate(skillsMatch.range.first, skills = true)
      commandMatch == 0 -> openOrUpdate(0, skills = false)
      else -> close()
    }
  }

  private fun openOrUpdate(at: Int, skills: Boolean) {
    anchor = at
    skillsMode = skills
    if (popup == null) show()
    refresh()
  }

  private fun show() {
    list.fixedCellWidth = popupWidth()
    val created = PopupChooserBuilder(list)
      .setRequestFocus(false)
      .setCancelKeyEnabled(false)
      .setAutoSelectIfEmpty(false)
      .setResizable(false)
      .setCloseOnEnter(false)
      .setFocusOwners(arrayOf<Component>(textArea))
      .setNorthComponent(header)
      .addListener(object : JBPopupListener {
        override fun onClosed(event: LightweightWindowEvent) {
          popup = null
          anchor = -1
          skillsMode = false
          model.removeAll()
        }
      })
      .createPopup()
      .also { com.vibe.agent.ui.VibeScroll.thinAllIn(it.content) }
    popup = created
    created.showInScreenCoordinates(textArea, placement(created.content.preferredSize))
  }

  private fun filter(): String {
    val text = textArea.text
    val caret = textArea.caretPosition.coerceAtMost(text.length)
    if (anchor < 0 || anchor >= caret) return ""
    val raw = text.substring(anchor, caret)
    return if (skillsMode) raw.removePrefix(SKILL_PREFIX) else raw.removePrefix(SLASH)
  }

  private fun refresh() {
    val query = filter().lowercase()
    val rows: List<Row>
    if (skillsMode) {
      header.text = SKILLS_HEADER
      val skills = SkillsRegistry.list(project.basePath)
      rows = when {
        skills.isEmpty() -> listOf(Row(null, NO_SKILLS_TEXT, ""))
        else -> skills.filter { it.id.lowercase().contains(query) }
          .map { Row("$SKILL_PREFIX${it.id} ", "$SKILL_PREFIX${it.id}", it.description) }
          .ifEmpty { listOf(Row(null, t("slash.noSkillsFor", "filter" to filter()), "")) }
      }
    }
    else {
      header.text = COMMANDS_HEADER
      rows = COMMANDS.filter { it.title.lowercase().contains("$SLASH$query".lowercase()) || query.isEmpty() }
        .ifEmpty { listOf(Row(null, t("slash.noCommandsFor", "filter" to filter()), "")) }
    }
    model.replaceAll(rows)
    list.visibleRowCount = rows.size.coerceIn(1, MAX_VISIBLE_ROWS)
    val first = (0 until model.size).firstOrNull { model.getElementAt(it).insert != null || model.getElementAt(it).toSkills }
    if (first == null) list.clearSelection() else ScrollingUtil.selectItem(list, first)
    popup?.let {
      if (it.isVisible) {
        it.pack(true, true)
        it.setLocation(placement(it.size))
      }
    }
  }

  // ---- picking -------------------------------------------------------------------------------

  private fun pickSelected() {
    val row = list.selectedValue ?: return
    when {
      row.toSkills -> {
        // Replace whatever was typed with the exact `/skill:` prefix; the menu flips to skills.
        replaceRange(SKILL_PREFIX)
        skillsMode = true
        refresh()
      }
      row.insert != null -> {
        replaceRange(row.insert)
        close()
      }
    }
  }

  private fun replaceRange(with: String) {
    val doc = textArea.document
    val caret = textArea.caretPosition.coerceAtMost(doc.length)
    if (anchor in 0 until caret) {
      doc.remove(anchor, caret - anchor)
      doc.insertString(anchor, with, null)
    }
  }

  // ---- keys / layout -------------------------------------------------------------------------

  private fun installShortcuts() {
    shortcut({ ScrollingUtil.moveUp(list, 0) }, key(KeyEvent.VK_UP))
    shortcut({ ScrollingUtil.moveDown(list, 0) }, key(KeyEvent.VK_DOWN))
    shortcut({ pickSelected() }, key(KeyEvent.VK_ENTER), key(KeyEvent.VK_TAB))
  }

  private fun key(code: Int): KeyStroke = KeyStroke.getKeyStroke(code, 0)

  private fun shortcut(perform: () -> Unit, vararg keys: KeyStroke) {
    object : DumbAwareAction() {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
      override fun update(e: AnActionEvent) { e.presentation.isEnabled = isShowing }
      override fun actionPerformed(e: AnActionEvent) = perform()
    }.registerCustomShortcutSet(CustomShortcutSet(*keys.map { com.intellij.openapi.actionSystem.KeyboardShortcut(it, null) }.toTypedArray()), textArea, this)
  }

  private fun popupWidth(): Int = (textArea.width - JBUI.scale(POPUP_CHROME_WIDTH)).coerceAtLeast(JBUI.scale(MIN_POPUP_WIDTH))

  private fun placement(size: Dimension): Point {
    val origin = textArea.locationOnScreen
    val gap = JBUI.scale(POPUP_GAP)
    val screen = ScreenUtil.getScreenRectangle(origin)
    val below = Point(origin.x, origin.y + textArea.height + gap)
    return if (below.y + size.height <= screen.y + screen.height) below else Point(origin.x, origin.y - gap - size.height)
  }

  private class RowRenderer : ColoredListCellRenderer<Row>() {
    override fun customizeCellRenderer(list: JList<out Row>, value: Row, index: Int, selected: Boolean, hasFocus: Boolean) {
      if (value.insert == null && !value.toSkills) {
        append(value.title, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        return
      }
      append(value.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
      if (value.detail.isNotEmpty()) append("  ${value.detail}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }
  }

  private companion object {
    const val SLASH = "/"
    const val SKILL_PREFIX = "/skill:"
    val SKILL_TAIL = Regex("/skill:[\\w-]*$")
    val COMMANDS_HEADER: String get() = t("slash.header.commands")
    const val SKILLS_HEADER = "Skills:"
    val NO_SKILLS_TEXT: String get() = t("slash.noSkills")
    const val MAX_VISIBLE_ROWS = 10
    const val MIN_POPUP_WIDTH = 320
    const val POPUP_CHROME_WIDTH = 12
    const val POPUP_GAP = 2
    const val HEADER_PAD_V = 6
    const val HEADER_PAD_H = 10

    val COMMANDS: List<Row> get() = listOf(
      Row("/commit ", "/commit", t("slash.command.commit")),
      Row("/git ", "/git", t("slash.command.git")),
      Row("/council ", "/council", t("slash.command.council")),
      Row("/handoff ", "/handoff", t("slash.command.handoff")),
      Row("/trace", "/trace", t("slash.command.trace")),
      Row("/output ", "/output", t("slash.command.output")),
      Row(null, "/skill:", t("slash.command.skill"), toSkills = true),
    )
  }
}

/** Shared muted colors of the composer popups (declared once for the `@` and `/` menus). */
object MentionColors {
  val HINT_FG = com.intellij.ui.JBColor.namedColor("Vibe.Mention.hintForeground",
    com.intellij.ui.JBColor.namedColor("Label.infoForeground", com.intellij.ui.JBColor.GRAY))
}
