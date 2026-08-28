// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.ui.popup.PopupChooserBuilder
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.ScreenUtil
import com.intellij.ui.ScrollingUtil
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.Callable
import javax.swing.Icon
import javax.swing.JList
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.CaretListener
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The `@` context menu of the chat composer (VibeIDE semantics): typing `@` at the start of the
 * text or after whitespace opens a list popup next to [textArea]; the text area keeps focus, the
 * filter is the text between the `@` and the caret. Navigation keys are component-local
 * shortcuts (`registerCustomShortcutSet`) enabled only while the menu is open: the IDE keymap
 * routes Backspace/arrows/Enter on any text component through its editor actions before Swing
 * listeners, and local shortcuts are the one thing that outranks the keymap. Picking a leaf
 * removes `@filter` from the document and hands a [ContextRef] to [onPick].
 *
 * Threading: the document and the list are touched only on the EDT; index queries run in
 * `ReadAction.nonBlocking` on the application pool and are discarded when stale.
 */
class MentionPopup(
  private val project: Project,
  private val textArea: JTextArea,
  parentDisposable: Disposable,
  private val selectionProvider: () -> ContextRef.Selection?,
  private val onPick: (ContextRef) -> Unit,
) : Disposable {

  /** Sub-levels of the menu; the root is `level == null`. */
  private enum class Branch(val title: String, val icon: Icon) {
    SELECTION("Выделение", AllIcons.Actions.InSelection),
    RECENT("Недавние", AllIcons.Vcs.History),
    WORKSPACE("Воркспейс", AllIcons.Nodes.ModuleGroup),
    FILES("Файлы", AllIcons.Actions.Search),
    FOLDERS("Папки", AllIcons.Nodes.Folder),
  }

  private sealed interface Row {
    data class Branch(val level: MentionPopup.Branch) : Row
    data class Leaf(val ref: ContextRef, val icon: Icon, val name: String, val detail: String) : Row
    /** Not selectable by keyboard and ignored on activation. */
    data class Info(val text: String) : Row
  }

  private val model = CollectionListModel<Row>()
  private val list = JBList(model).apply {
    selectionMode = ListSelectionModel.SINGLE_SELECTION
    cellRenderer = RowRenderer()
    setEmptyText("")
  }
  private val header = JBLabel().apply { border = JBUI.Borders.empty(HEADER_PAD_V, HEADER_PAD_H, HEADER_PAD_V / 2, HEADER_PAD_H) }
  private val alarm = Alarm(this)

  private var popup: JBPopup? = null
  /** Offset of the `@` in the document, -1 while closed. */
  private var anchor = -1
  private var level: Branch? = null
  private var index: MentionIndex? = null
  /** Bumped on every refresh; a finished search whose generation is older is dropped. */
  private var generation = 0
  private var lastRefresh: Pair<Branch?, String>? = null

  private val documentListener = object : DocumentListener {
    override fun insertUpdate(e: DocumentEvent) {
      if (!isShowing && isTrigger(e)) SwingUtilities.invokeLater { open(e.offset) }
      else if (isShowing) SwingUtilities.invokeLater { syncWithDocument() }
    }
    override fun removeUpdate(e: DocumentEvent) { if (isShowing) SwingUtilities.invokeLater { syncWithDocument() } }
    override fun changedUpdate(e: DocumentEvent) {}
  }
  private val caretListener = CaretListener { if (isShowing) SwingUtilities.invokeLater { syncWithDocument() } }
  private val focusListener = object : FocusAdapter() {
    override fun focusLost(e: FocusEvent) {
      val content = popup?.content ?: return
      val opposite = e.oppositeComponent
      if (opposite == null || !SwingUtilities.isDescendingFrom(opposite, content)) close()
    }
  }

  init {
    textArea.document.addDocumentListener(documentListener)
    textArea.addCaretListener(caretListener)
    textArea.addFocusListener(focusListener)
    list.addMouseListener(object : MouseAdapter() {
      override fun mouseReleased(e: MouseEvent) {
        if (!SwingUtilities.isLeftMouseButton(e)) return
        val i = list.locationToIndex(e.point)
        if (i >= 0 && list.getCellBounds(i, i)?.contains(e.point) == true) {
          e.consume()
          activate(model.getElementAt(i))
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
    textArea.removeCaretListener(caretListener)
    textArea.removeFocusListener(focusListener)
  }

  // ---- opening / closing -------------------------------------------------------------------

  /** `@` counts as a trigger only at the text start or right after whitespace. */
  private fun isTrigger(e: DocumentEvent): Boolean {
    if (e.length != 1) return false
    val doc = e.document
    if (doc.getText(e.offset, 1) != TRIGGER) return false
    return e.offset == 0 || doc.getText(e.offset - 1, 1).single().isWhitespace()
  }

  private fun open(atOffset: Int) {
    // Runs via invokeLater: the trigger may already have been edited away.
    val doc = textArea.document
    if (isShowing || atOffset < 0 || atOffset >= doc.length || doc.getText(atOffset, 1) != TRIGGER) return
    anchor = atOffset
    level = null
    index = MentionIndex(project)
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
        override fun onClosed(event: LightweightWindowEvent) = onPopupClosed()
      })
      .createPopup()
      .also { com.vibe.agent.ui.VibeScroll.thinAllIn(it.content) }
    popup = created
    lastRefresh = null to ""
    showRows(rootRows(), filter = "")
    created.showInScreenCoordinates(textArea, placement(created.content.preferredSize))
  }

  private fun onPopupClosed() {
    popup = null
    anchor = -1
    level = null
    index = null
    lastRefresh = null
    generation++
    alarm.cancelAllRequests()
    model.removeAll()
  }

  /** Closes the menu and removes the `@` that opened it (Backspace on an empty root filter). */
  private fun closeAndDeleteTrigger() {
    val at = anchor
    close()
    val doc = textArea.document
    if (at in 0 until doc.length && doc.getText(at, 1) == TRIGGER) doc.remove(at, 1)
  }

  private fun pick(ref: ContextRef) {
    val start = anchor
    val end = textArea.caretPosition
    close()
    val doc = textArea.document
    if (start in 0 until doc.length && end > start && end <= doc.length) doc.remove(start, end - start)
    onPick(ref)
  }

  // ---- filter / document sync ----------------------------------------------------------------

  /** Text between the `@` and the caret; null when the document no longer carries a live trigger. */
  private fun currentFilter(): String? {
    val text = textArea.text
    val caret = textArea.caretPosition
    if (anchor < 0 || anchor >= text.length || text[anchor] != TRIGGER[0] || caret <= anchor) return null
    val filter = text.substring(anchor + 1, caret)
    return if (filter.any { it.isWhitespace() }) null else filter
  }

  /** Document and caret listeners both fire per keystroke; identical (level, filter) pairs are refreshed once. */
  private fun syncWithDocument() {
    if (!isShowing) return
    val filter = currentFilter() ?: return close()
    if (level to filter != lastRefresh) refresh()
  }

  private fun refresh() {
    val idx = index ?: return
    val filter = currentFilter() ?: return close()
    lastRefresh = level to filter
    // Invalidate debounced and in-flight searches: their rows belong to a previous filter.
    generation++
    alarm.cancelAllRequests()
    when (level) {
      null -> if (filter.isEmpty()) showRows(rootRows(), filter)
              else searchInIndex(filter, debounce = true) { it.searchBoth(filter, MAX_ITEMS).map(::leaf) }
      Branch.SELECTION -> showRows(selectionRows(idx, filter), filter)
      Branch.RECENT -> search(filter, debounce = false) { nameFiltered(it.recent(MAX_ITEMS), filter, it) }
      Branch.WORKSPACE -> search(filter, debounce = false) { nameFiltered(it.workspaceRoots(), filter, it) }
      Branch.FILES -> if (filter.isEmpty()) showRows(emptyList(), filter)
                      else searchInIndex(filter, debounce = true) { it.searchFiles(filter, MAX_ITEMS).map(::leaf) }
      Branch.FOLDERS -> if (filter.isEmpty()) showRows(emptyList(), filter)
                        else searchInIndex(filter, debounce = true) { it.searchFolders(filter, MAX_ITEMS).map(::leaf) }
    }
  }

  private fun nameFiltered(items: List<MentionIndex.Item>, filter: String, idx: MentionIndex): List<Row> {
    if (filter.isEmpty()) return items.map(::leaf)
    val matcher = idx.matcher(filter)
    return items.filter { matcher.matches(it.ref.file.name) }.map(::leaf)
  }

  /** Index-backed levels fall back to a notice while the project is being indexed. */
  private fun searchInIndex(filter: String, debounce: Boolean, compute: (MentionIndex) -> List<Row>) {
    val idx = index ?: return
    if (!idx.isReady) {
      showRows(listOf(Row.Info(INDEXING_TEXT)), filter)
      return
    }
    search(filter, debounce, compute)
  }

  private fun search(filter: String, debounce: Boolean, compute: (MentionIndex) -> List<Row>) {
    val idx = index ?: return
    val gen = generation
    val task = Runnable {
      if (gen != generation) return@Runnable
      ReadAction.nonBlocking(Callable {
        try { compute(idx) } catch (ignored: IndexNotReadyException) { listOf(Row.Info(INDEXING_TEXT)) }
      })
        .coalesceBy(this)
        .expireWith(this)
        .finishOnUiThread(ModalityState.any()) { rows -> if (gen == generation && isShowing) showRows(rows, filter) }
        .submit(AppExecutorUtil.getAppExecutorService())
    }
    if (debounce) alarm.addRequest(task, DEBOUNCE_MS) else task.run()
  }

  // ---- rows --------------------------------------------------------------------------------

  private fun rootRows(): List<Row> = Branch.entries.map(Row::Branch)

  private fun selectionRows(idx: MentionIndex, filter: String): List<Row> {
    val selection = selectionProvider() ?: return listOf(Row.Info(NO_SELECTION_TEXT))
    val name = "${selection.file.name}:${selection.fromLine}-${selection.toLine}"
    if (filter.isNotEmpty() && !idx.matcher(filter).matches(name)) return emptyList()
    return listOf(Row.Leaf(selection, fileIcon(selection.file), name, parentPath(idx.displayPath(selection.file))))
  }

  private fun leaf(item: MentionIndex.Item): Row.Leaf {
    val file = item.ref.file
    val icon = if (item.ref is ContextRef.Folder) AllIcons.Nodes.Folder else fileIcon(file)
    return Row.Leaf(item.ref, icon, file.name, parentPath(item.path))
  }

  private fun fileIcon(file: VirtualFile): Icon = file.fileType.icon ?: AllIcons.FileTypes.Any_type

  private fun parentPath(path: String): String = path.substringBeforeLast('/', "")

  // ---- list state / layout -----------------------------------------------------------------

  private fun showRows(rows: List<Row>, filter: String) {
    model.replaceAll(rows)
    list.visibleRowCount = rows.size.coerceIn(1, MAX_VISIBLE_ROWS)
    selectFirstSelectable()
    updateHeader(rows, filter)
    relayout()
  }

  private fun updateHeader(rows: List<Row>, filter: String) {
    val (text, muted) = when {
      level == null && filter.isEmpty() -> "" to true
      filter.isEmpty() -> PROMPT_TEXT to true
      rows.isEmpty() -> NOT_FOUND_TEXT to true
      else -> filter to false
    }
    header.text = text
    header.isVisible = text.isNotEmpty()
    header.foreground = if (muted) HINT_FG else HEADER_FG
  }

  private fun selectFirstSelectable() {
    val i = (0 until model.size).firstOrNull { model.getElementAt(it) !is Row.Info }
    if (i == null) list.clearSelection() else ScrollingUtil.selectItem(list, i)
  }

  private fun popupWidth(): Int = (textArea.width - JBUI.scale(POPUP_CHROME_WIDTH)).coerceAtLeast(JBUI.scale(MIN_POPUP_WIDTH))

  /** Below the text area when it fits on screen, otherwise above — the composer usually sits at the bottom. */
  private fun placement(size: Dimension): Point {
    val origin = textArea.locationOnScreen
    val gap = JBUI.scale(POPUP_GAP)
    val screen = ScreenUtil.getScreenRectangle(origin)
    val below = Point(origin.x, origin.y + textArea.height + gap)
    val fitsBelow = below.y + size.height <= screen.y + screen.height
    return if (fitsBelow) below else Point(origin.x, origin.y - gap - size.height)
  }

  private fun relayout() {
    val p = popup ?: return
    if (!p.isVisible) return
    p.pack(true, true)
    p.setLocation(placement(p.size))
  }

  // ---- keyboard ----------------------------------------------------------------------------

  /**
   * Component-local shortcuts, live only while the menu is open. Esc is owned by the composer
   * (it closes the menu first, then stops a running turn); plain Enter here outranks the
   * composer's Swing-level Enter-to-send, Shift+Enter falls through to the line break.
   */
  private fun installShortcuts() {
    val edge = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
    shortcut({ move(-1, toEdge = false, jump = false) }, key(KeyEvent.VK_UP))
    shortcut({ move(1, toEdge = false, jump = false) }, key(KeyEvent.VK_DOWN))
    shortcut({ move(-1, toEdge = false, jump = true) }, key(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK))
    shortcut({ move(1, toEdge = false, jump = true) }, key(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK))
    shortcut({ move(-1, toEdge = true, jump = false) }, key(KeyEvent.VK_UP, edge))
    shortcut({ move(1, toEdge = true, jump = false) }, key(KeyEvent.VK_DOWN, edge))
    shortcut({ list.selectedValue?.let(::activate) }, key(KeyEvent.VK_RIGHT), key(KeyEvent.VK_ENTER))
    // At the root the caret may move left; stepping before the `@` closes the menu via syncWithDocument().
    shortcut({ back() }, key(KeyEvent.VK_LEFT), enabled = { level != null })
    shortcut({ if (level == null) closeAndDeleteTrigger() else back() }, key(KeyEvent.VK_BACK_SPACE),
             enabled = { currentFilter().isNullOrEmpty() })
  }

  private fun key(code: Int, modifiers: Int = 0): KeyStroke = KeyStroke.getKeyStroke(code, modifiers)

  private fun shortcut(perform: () -> Unit, vararg keys: KeyStroke, enabled: () -> Boolean = { true }) {
    object : DumbAwareAction() {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
      override fun update(e: AnActionEvent) { e.presentation.isEnabled = isShowing && enabled() }
      override fun actionPerformed(e: AnActionEvent) = perform()
    }.registerCustomShortcutSet(CustomShortcutSet(*keys.map { KeyboardShortcut(it, null) }.toTypedArray()), textArea, this)
  }

  /** Single steps wrap around; edge and jump moves clamp. Info rows are skipped. */
  private fun move(direction: Int, toEdge: Boolean, jump: Boolean) {
    val size = model.size
    if (size == 0) return
    val current = list.selectedIndex
    var target = when {
      toEdge -> if (direction < 0) 0 else size - 1
      jump -> (if (current < 0) 0 else current + direction * JUMP).coerceIn(0, size - 1)
      current < 0 -> if (direction < 0) size - 1 else 0
      else -> Math.floorMod(current + direction, size)
    }
    var guard = size
    while (model.getElementAt(target) is Row.Info && guard-- > 0) target = Math.floorMod(target + direction, size)
    if (model.getElementAt(target) !is Row.Info) ScrollingUtil.selectItem(list, target)
  }

  private fun activate(row: Row) {
    when (row) {
      is Row.Branch -> { level = row.level; refresh() }
      is Row.Leaf -> pick(row.ref)
      is Row.Info -> {}
    }
  }

  private fun back() {
    if (level == null) return
    level = null
    refresh()
  }

  // ---- rendering ---------------------------------------------------------------------------

  private class RowRenderer : ColoredListCellRenderer<Row>() {
    override fun customizeCellRenderer(list: JList<out Row>, value: Row, index: Int, selected: Boolean, hasFocus: Boolean) {
      when (value) {
        is Row.Branch -> {
          icon = value.level.icon
          append(value.level.title, SimpleTextAttributes.REGULAR_ATTRIBUTES)
          append(BRANCH_SUFFIX, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        is Row.Leaf -> {
          icon = value.icon
          append(value.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
          if (value.detail.isNotEmpty()) append(DETAIL_SEPARATOR + value.detail, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        is Row.Info -> {
          icon = null
          append(value.text, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
      }
    }
  }

  private companion object {
    const val TRIGGER = "@"
    const val MAX_ITEMS = 100
    const val DEBOUNCE_MS = 300
    const val JUMP = 10
    const val MAX_VISIBLE_ROWS = 12
    const val MIN_POPUP_WIDTH = 320
    /** Popup border plus list viewport padding, subtracted so the popup matches the text area width. */
    const val POPUP_CHROME_WIDTH = 12
    const val POPUP_GAP = 2
    const val HEADER_PAD_V = 6
    const val HEADER_PAD_H = 10
    const val BRANCH_SUFFIX = " ›"
    const val DETAIL_SEPARATOR = "  "

    const val PROMPT_TEXT = "Введите текст для фильтра…"
    const val NOT_FOUND_TEXT = "Ничего не найдено"
    const val NO_SELECTION_TEXT = "Нет выделения"
    const val INDEXING_TEXT = "Индексация… попробуйте чуть позже"

    val HEADER_FG = JBColor.namedColor("Vibe.Mention.headerForeground", JBColor.namedColor("Label.foreground", JBColor.foreground()))
    val HINT_FG = JBColor.namedColor("Vibe.Mention.hintForeground", JBColor.namedColor("Label.infoForeground", JBColor.GRAY))
  }
}
