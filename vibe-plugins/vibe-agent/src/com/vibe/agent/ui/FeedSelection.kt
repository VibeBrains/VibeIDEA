// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.vibe.agent.i18n.VibeI18n.t
import java.awt.Point
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.ContainerEvent
import java.awt.event.ContainerListener
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

/**
 * Selecting and copying text ACROSS the messages of the feed.
 *
 * Swing selects inside one component, and the feed is a stack of many: prose, rendered markdown,
 * code blocks, service lines. Everything the owner tried therefore stopped at a component boundary
 * — Ctrl+A took one piece of prose and halted at the next code block, a triple click took a wrapped
 * visual row (Swing selects a ROW, not the message), and a drag died at the edge of a bubble.
 *
 * The parts are each free to paint their own selection, so the answer is not one giant text area —
 * it is one selection told to many components. This class owns the two anchors; the arithmetic is
 * [FeedSelectionModel]'s, so it can be tested without a mouse.
 *
 * Attached once to the feed panel: components are hooked as they are ADDED, recursively, because
 * the feed is rebuilt on every thread switch and a registry filled by hand would drift from it.
 */
class FeedSelection(private val feed: JComponent, private val scroll: JScrollPane?) {
  /**
   * Разговор в Markdown — тем же текстом, что уходит в файл при экспорте.
   *
   * Копия «как на экране» теряет то, чего на экране нет: кто говорил, когда, что вернули
   * инструменты. Один формат на копирование и на файл — чтобы вставленное в письмо и сохранённое
   * рядом не оказались двумя разными разговорами.
   */
  var markdown: (() -> String?)? = null
  private var anchor: FeedSelectionModel.Anchor? = null
  private var dragging = false
  /**
   * Where the mouse was last, in the VIEWPORT's coordinates (the feed's when there is no scroll).
   *
   * Not the feed's: the feed can move under a mouse that is not moving (the person scrolls with the
   * wheel while holding the button), and the same feed point would then mean two different places.
   */
  private var lastDragPoint: Point? = null

  fun install() {
    hook(feed)
    feed.addMouseListener(object : MouseAdapter() {
      override fun mousePressed(e: MouseEvent) = onPress(e)
      override fun mouseReleased(e: MouseEvent) = onRelease(e)
    })
    feed.addMouseMotionListener(object : MouseAdapter() {
      override fun mouseDragged(e: MouseEvent) = onDrag(e)
    })
    bindKeys(feed)
  }

  /**
   * Whether anything is selected right now — the feed asks before following the stream.
   *
   * Following and selecting cannot both win: a feed that jumps to the bottom while a selection is
   * being made moves the text out from under the mouse. So the stream waits, and the person gets
   * their selection; the «в конец» button is one click away when they are done.
   */
  fun isActive(): Boolean = dragging || pieces().any { it.selectionStart != it.selectionEnd }

  /** Pieces of the feed in reading order — recomputed on demand, so a rebuilt feed needs no notice. */
  private fun pieces(): List<JTextComponent> {
    val found = ArrayList<JTextComponent>()
    collect(feed, found)
    return found
  }

  private fun collect(component: java.awt.Component, into: MutableList<JTextComponent>) {
    if (component is JTextComponent) {
      if (component.isShowing) into.add(component)
      return
    }
    (component as? java.awt.Container)?.components?.forEach { collect(it, into) }
  }

  private fun hook(container: java.awt.Container) {
    container.addContainerListener(object : ContainerListener {
      override fun componentAdded(e: ContainerEvent) = hookTree(e.child)
      override fun componentRemoved(e: ContainerEvent) {}
    })
    container.components.forEach { hookTree(it) }
  }

  private fun hookTree(component: java.awt.Component) {
    if (component is JTextComponent) {
      hookText(component)
      return
    }
    (component as? java.awt.Container)?.let { hook(it) }
  }

  private fun hookText(text: JTextComponent) {
    if (text.getClientProperty(HOOKED) == true) return
    text.putClientProperty(HOOKED, true)
    text.caret = QuietCaret()
    text.addMouseListener(object : MouseAdapter() {
      override fun mousePressed(e: MouseEvent) = onPress(e)
      override fun mouseReleased(e: MouseEvent) = onRelease(e)
      override fun mouseClicked(e: MouseEvent) {
        if (e.clickCount < TRIPLE_CLICK || e.isPopupTrigger) return
        // Swing's own triple click takes the visual ROW: with wrapping on, that is a fragment of a
        // sentence and never what someone means by «выдели это».
        val index = pieces().indexOf(text)
        if (index < 0) return
        anchor = FeedSelectionModel.Anchor(index, 0)
        apply(FeedSelectionModel.Anchor(index, text.document.length))
      }
    })
    text.addMouseMotionListener(object : MouseAdapter() {
      override fun mouseDragged(e: MouseEvent) = onDrag(e)
    })
    bindKeys(text)
  }

  private fun onPress(e: MouseEvent) {
    if (e.isPopupTrigger) { popup(e); return }
    if (!SwingUtilities.isLeftMouseButton(e)) return
    remember(e)
    anchor = anchorAt(feedPoint() ?: return)
    dragging = true
    clearAll()
  }

  private fun onDrag(e: MouseEvent) {
    if (!dragging) return
    remember(e)
    extendToLastPoint()
  }

  private fun onRelease(e: MouseEvent) {
    dragging = false
    if (e.isPopupTrigger) popup(e)
  }

  private fun remember(e: MouseEvent) {
    val target: JComponent = scroll?.viewport ?: feed
    lastDragPoint = SwingUtilities.convertPoint(e.component, e.point, target)
  }

  private fun feedPoint(): Point? {
    val point = lastDragPoint ?: return null
    val source: JComponent = scroll?.viewport ?: return point
    return SwingUtilities.convertPoint(source, point, feed)
  }

  private fun extendToLastPoint() {
    val focus = anchorAt(feedPoint() ?: return) ?: return
    apply(focus)
  }

  /**
   * The place in the feed under a point.
   *
   * A point in the gap BETWEEN bubbles belongs to the piece above it, at its very end: dragging
   * through the padding must not tear the selection in half.
   */
  private fun anchorAt(point: Point): FeedSelectionModel.Anchor? {
    val items = pieces()
    if (items.isEmpty()) return null
    items.forEachIndexed { index, text ->
      val bounds = SwingUtilities.convertRectangle(text.parent, text.bounds, feed)
      if (point.y in bounds.y..(bounds.y + bounds.height)) {
        val local = SwingUtilities.convertPoint(feed, point, text)
        return FeedSelectionModel.Anchor(index, text.viewToModel2D(local).coerceIn(0, text.document.length))
      }
      if (point.y < bounds.y) {
        return if (index == 0) FeedSelectionModel.Anchor(0, 0)
        else FeedSelectionModel.Anchor(index - 1, items[index - 1].document.length)
      }
    }
    return FeedSelectionModel.Anchor(items.lastIndex, items.last().document.length)
  }

  private fun apply(focus: FeedSelectionModel.Anchor) {
    val start = anchor ?: return
    val items = pieces()
    val ranges = FeedSelectionModel.ranges(start, focus, items.map { it.document.length })
    show(items, ranges)
  }

  private fun show(items: List<JTextComponent>, ranges: List<FeedSelectionModel.Range>) {
    val selected = ranges.associateBy { it.index }
    items.forEachIndexed { index, text ->
      val range = selected[index]
      if (range == null || range.start >= range.end) {
        // select(x, x) rather than setSelectionStart: a caret left inside a bubble looks like a
        // selection of one character in some themes.
        text.select(0, 0)
      }
      else {
        text.select(range.start, range.end)
      }
    }
  }

  fun selectAll() {
    val items = pieces()
    val ends = FeedSelectionModel.wholeFeed(items.map { it.document.length }) ?: return
    anchor = ends.first
    show(items, FeedSelectionModel.all(items.map { it.document.length }))
  }

  fun clearAll() {
    pieces().forEach { it.select(0, 0) }
  }

  /** The selected text of every piece, in reading order — markdown comes back as words, not tags. */
  fun selectedText(): String = pieces().mapNotNull { it.selectedText?.takeIf(String::isNotBlank) }.joinToString("\n\n")

  /** The whole conversation as it reads, whatever is selected right now. */
  fun wholeText(): String = pieces().mapNotNull { it.text?.takeIf(String::isNotBlank) }.joinToString("\n\n")

  fun copySelection() {
    val text = selectedText().ifBlank { return }
    CopyPasteManager.getInstance().setContents(StringSelection(text))
  }

  private fun copyWhole() {
    val text = (markdown?.invoke()?.takeIf { it.isNotBlank() } ?: wholeText()).ifBlank { return }
    CopyPasteManager.getInstance().setContents(StringSelection(text))
  }

  private fun popup(e: MouseEvent) {
    val menu = JPopupMenu()
    menu.add(javax.swing.JMenuItem(t("chat.selection.copy")).apply {
      isEnabled = selectedText().isNotBlank()
      addActionListener { copySelection() }
    })
    menu.add(javax.swing.JMenuItem(t("chat.selection.selectAll")).apply { addActionListener { selectAll() } })
    menu.add(javax.swing.JMenuItem(t("chat.selection.copyAllMarkdown")).apply { addActionListener { copyWhole() } })
    menu.show(e.component, e.x, e.y)
  }

  /**
   * Ctrl/Cmd+A, Ctrl/Cmd+C and Esc — on the feed AND on every piece.
   *
   * Put into the component's own input map, which is consulted before the editor keymap the text
   * component inherits: that keymap is precisely the one that answered «this component only».
   */
  private fun bindKeys(component: JComponent) {
    val menuMask = java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
    // Both maps: the focused one for a piece the person clicked into, the ancestor one for the feed
    // itself — so the shortcut works from anything inside the feed, hooked or not.
    for (condition in intArrayOf(JComponent.WHEN_FOCUSED, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)) {
      val map = component.getInputMap(condition)
      map.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, menuMask), SELECT_ALL)
      map.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, menuMask), COPY)
      map.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), CLEAR)
    }
    component.actionMap.put(SELECT_ALL, object : AbstractAction() {
      override fun actionPerformed(e: ActionEvent) = selectAll()
    })
    component.actionMap.put(COPY, object : AbstractAction() {
      override fun actionPerformed(e: ActionEvent) = copySelection()
    })
    component.actionMap.put(CLEAR, object : AbstractAction() {
      override fun actionPerformed(e: ActionEvent) = clearAll()
    })
  }

  /**
   * A caret that never scrolls its component into view.
   *
   * The feed selects by telling EVERY piece what part of it is selected ([show]), and
   * `JTextComponent.select` is `setCaretPosition` plus `moveCaretPosition` — each of them ends in
   * `DefaultCaret.adjustVisibility`, which calls `scrollRectToVisible`. So a drag through a long
   * answer asked the feed to scroll to the START of each selected piece, several times per mouse
   * move: the feed jumped back to the first paragraph and selecting the tail of a long answer was
   * impossible (owner, 0.6.4, 18.09.2026).
   *
   * Only the SCROLLING is dropped, and nothing else: the caret still owns the selection highlight,
   * so the pieces paint it as before, and the feed's own auto-scroll at the viewport edge — ours,
   * by timer, while the mouse is held outside — is untouched.
   */
  private class QuietCaret : javax.swing.text.DefaultCaret() {
    init {
      // Streaming rewrites the document under the selection; a caret that follows the text would
      // move the selection with it.
      updatePolicy = NEVER_UPDATE
    }

    override fun adjustVisibility(nloc: java.awt.Rectangle?) {}
  }

  private companion object {
    const val HOOKED = "vibe.feedSelection.hooked"
    const val SELECT_ALL = "vibe.feed.selectAll"
    const val COPY = "vibe.feed.copy"
    const val CLEAR = "vibe.feed.clear"
    const val TRIPLE_CLICK = 3
  }
}
