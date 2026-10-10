// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.review

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.mcp.AgentEditJournal
import com.vibe.agent.ui.VibeNotifications
import java.awt.Cursor
import java.awt.Font

/**
 * The review of one file in one editor: the marks on its lines, the buttons on its hunks and the strip under it
 *
 * Everything here runs on the EDT. The rule of the review lives in [FileReview]; this class only shows it and writes its results
 * out: a rejected hunk into the document (one undoable command), an accepted one into the journal's baseline
 *
 * Several editors of one file (a split) each have their own session and their own place; they meet in the journal
 */
class ReviewSession(
  private val project: Project,
  val fileEditor: FileEditor,
  val editor: EditorEx,
  val file: VirtualFile,
  /** The path the journal knows the file by */
  val journalPath: String,
  private val service: AgentReviewService,
) : Disposable {
  private val journal = AgentEditJournal.getInstance(project)
  private val document: Document = editor.document

  /** Through [Editor]: the model [EditorEx] hands out is an extended one that lives in a module we do not need */
  private val markup: MarkupModel = (editor as Editor).markupModel
  private val highlighters = ArrayList<RangeHighlighter>()
  private val blocks = ArrayList<Inlay<HunkBlock>>()
  private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
  private lateinit var review: FileReview

  /** What the agent last wrote, until the document shows it: that is the moment its hunks start from the first */
  private var agentWrote: String? = null
  private var drawnStamp = Long.MIN_VALUE
  private var drawnPlace: Int? = null
  private var drawnHunks: List<Hunk> = emptyList()
  private var hovered: Hit? = null
  private var disposed = false

  private val bar = ReviewBar(
    onPreviousHunk = { step(forward = false) },
    onNextHunk = { step(forward = true) },
    onPreviousFile = { service.openNeighbour(journalPath, -1) },
    onNextFile = { service.openNeighbour(journalPath, 1) },
    onAcceptFile = { acceptFile() },
    onRejectFile = { rejectFile() },
  )

  private val documentListener = object : DocumentListener {
    override fun documentChanged(event: DocumentEvent) = schedule()
  }

  private val mouseListener = object : EditorMouseListener {
    // The press is taken as well, so a click on a button does not start a selection in the text behind it
    override fun mousePressed(e: EditorMouseEvent) {
      if (hit(e) != null) e.consume()
    }

    override fun mouseExited(e: EditorMouseEvent) = hover(null)

    override fun mouseClicked(e: EditorMouseEvent) {
      val hit = hit(e) ?: return
      e.consume()
      when (hit.button) {
        HunkBlock.Button.ACCEPT -> accept(hit.block.hunk)
        HunkBlock.Button.REJECT -> reject(hit.block.hunk)
      }
    }
  }

  private val motionListener = object : EditorMouseMotionListener {
    override fun mouseMoved(e: EditorMouseEvent) = hover(hit(e))
  }

  /** Whether there is anything to resolve; the key actions are on only then */
  val hasHunks: Boolean get() = this::review.isInitialized && review.size > 0

  /** False when the file has no entry in the journal, and then there is nothing to show */
  fun start(): Boolean {
    val entry = entry() ?: return false
    review = FileReview(entry.before.orEmpty(), document.text)
    document.addDocumentListener(documentListener, this)
    editor.addEditorMouseListener(mouseListener, this)
    editor.addEditorMouseMotionListener(motionListener, this)
    FileEditorManager.getInstance(project).addBottomComponent(fileEditor, bar)
    render()
    return true
  }

  /** The journal says something about this file; what it says is read at once, so the marks never lag behind the entry */
  fun onJournal(change: AgentEditJournal.Change) {
    if (change is AgentEditJournal.Change.Recorded) agentWrote = change.after
    flush()
  }

  /** The strip's counters; the journal may have gained or lost other files */
  fun showBar() {
    if (disposed || !this::review.isInitialized) return
    bar.show(review.place, review.size, service.indexOf(journalPath).coerceAtLeast(0), service.fileCount().coerceAtLeast(1))
  }

  /** The line the reader is on, for the editor to open at; null when there is no hunk */
  fun currentLine(): Int? = review.place?.let { review.hunks.getOrNull(it) }?.let { it.afterStart.coerceIn(0, document.lineCount - 1) }

  fun step(forward: Boolean) {
    flush()
    review.step(forward)
    render()
    reveal()
  }

  /** Accepts [target], or the current hunk when it is null; the place goes to the hunk that followed */
  fun accept(target: Hunk? = null) {
    flush()
    val idx = indexOf(target) ?: return
    val position = service.indexOf(journalPath)
    val newBefore = review.accept(idx) ?: return
    val settled = review.size == 0
    journal.rebase(journalPath, newBefore, settled)
    resolved(settled, position)
  }

  /** Rejects [target], or the current hunk when it is null; the place goes to the hunk that followed */
  fun reject(target: Hunk? = null) {
    flush()
    val idx = indexOf(target) ?: return
    val entry = entry() ?: return
    // A created file is one hunk over all of it: rejecting that hunk is rejecting the file, and the file goes
    if (entry.before == null) {
      rejectFile()
      return
    }
    if (!document.isWritable) {
      warn(t("review.failed", "path" to file.name, "reason" to t("review.readOnly")))
      return
    }
    val position = service.indexOf(journalPath)
    val wasAgentsText = EditHunks.normalize(entry.after) == EditHunks.normalize(document.text)
    val newText = review.reject(idx) ?: return
    write(newText, t("review.command.reject"))
    val settled = review.size == 0
    // The recorded text follows only an untouched agent text, so a person's own edit still reads as drift to the chat's Reject
    journal.follow(journalPath, if (wasAgentsText) EditHunks.likeOriginal(entry.after, newText) else null, settled)
    resolved(settled, position)
  }

  /** The whole file is accepted: what is in it stays, and the entry goes */
  fun acceptFile() {
    flush()
    val position = service.indexOf(journalPath)
    journal.accept(journalPath)
    service.openAfterResolve(position)
  }

  /**
   * The whole file is rejected: the baseline comes back into the document, one undoable command; a file the agent created is deleted
   * What the reader sees on screen is what goes, so there is no drift check here as the chat's Reject has
   */
  fun rejectFile() {
    flush()
    val entry = entry() ?: return
    val position = service.indexOf(journalPath)
    try {
      if (entry.before == null) {
        WriteCommandAction.runWriteCommandAction(project, t("review.command.rejectFile"), null, Runnable { file.delete(this) })
      }
      else if (!write(EditHunks.normalize(entry.before), t("review.command.rejectFile"))) {
        return
      }
    }
    catch (e: java.io.IOException) {
      warn(t("review.failed", "path" to file.name, "reason" to (e.message ?: e.javaClass.simpleName)))
      return
    }
    journal.accept(journalPath)
    service.openAfterResolve(position)
  }

  /** Brings the current hunk into view, unless it already is: a screen that moves under a reader who sees the hunk is a jolt */
  fun reveal() {
    if (disposed) return
    val hunk = review.place?.let { review.hunks.getOrNull(it) } ?: return
    val lines = document.lineCount
    val first = hunk.afterStart.coerceIn(0, lines - 1)
    val last = if (hunk.afterEnd > hunk.afterStart) (hunk.afterEnd - 1).coerceIn(first, lines - 1) else first
    val view = editor.scrollingModel.visibleArea
    // The top of a line's slot is where the blocks above it start, so the hunk's own toolbar counts as part of it
    val top = editor.visualLineToY(editor.logicalToVisualPosition(LogicalPosition(first, 0)).line)
    val bottom = editor.visualLineToYRange(editor.logicalToVisualPosition(LogicalPosition(last, 0)).line)[1]
    if (view.height > 0 && !HunkNavigation.needsScroll(top, bottom, view.y, view.y + view.height)) return
    editor.scrollingModel.scrollTo(LogicalPosition(first, 0), ScrollType.CENTER)
  }

  override fun dispose() {
    if (disposed) return
    disposed = true
    if (!editor.isDisposed) {
      clearMarks()
      editor.setCustomCursor(this, null)
    }
    FileEditorManager.getInstance(project).removeBottomComponent(fileEditor, bar)
    service.forget(this)
  }

  private fun entry(): AgentEditJournal.Entry? = journal.all().firstOrNull { it.path == journalPath }

  /** The hunk's place in the list now; null when the list moved on and the hunk is gone */
  private fun indexOf(target: Hunk?): Int? {
    if (target == null) return review.place
    return review.hunks.indexOf(target).takeIf { it >= 0 }
  }

  private fun schedule() {
    if (disposed) return
    alarm.cancelAllRequests()
    alarm.addRequest({ sync() }, DEBOUNCE_MS)
  }

  /** Catches up with the document and the journal at once; every action starts here, so it never works on a stale list */
  private fun flush() {
    alarm.cancelAllRequests()
    sync()
  }

  private fun sync() {
    if (disposed) return
    // No entry: the journal removed it, and the service is on its way to dispose this session
    val entry = entry() ?: return
    val text = document.text
    review.updateBefore(entry.before.orEmpty())
    review.updateCurrent(text)
    restartIfWritten(text)
    if (document.modificationStamp != drawnStamp || review.place != drawnPlace || review.hunks != drawnHunks) render()
  }

  /** The agent's text is on screen now: its hunks are new to the reader, who starts them from the first */
  private fun restartIfWritten(text: String) {
    val written = agentWrote ?: return
    if (EditHunks.normalize(written) != EditHunks.normalize(text)) return
    agentWrote = null
    review.restart()
  }

  private fun resolved(settled: Boolean, position: Int) {
    if (settled) {
      // The journal has dropped the file; the service disposes this session and the reader goes on to the next file
      service.openAfterResolve(position)
      return
    }
    render()
    reveal()
  }

  /** False when the document could not be written to (read-only); the reader is told */
  private fun write(text: String, command: String): Boolean {
    if (!document.isWritable) {
      warn(t("review.failed", "path" to file.name, "reason" to t("review.readOnly")))
      return false
    }
    val edit = EditHunks.minimalEdit(document.text, text)
    if (edit != null) {
      WriteCommandAction.runWriteCommandAction(project, command, null, Runnable {
        document.replaceString(edit.start, edit.end, edit.replacement)
      })
    }
    // Saved at once, as the agent's own writes are: the chat's Reject reads the file, not the unsaved document
    FileDocumentManager.getInstance().saveDocument(document)
    return true
  }

  private fun render() {
    if (disposed || editor.isDisposed) return
    clearMarks()
    val lines = document.lineCount
    val tabSize = editor.settings.getTabSize(project)
    review.hunks.forEachIndexed { index, hunk ->
      val firstLine = hunk.afterStart.coerceIn(0, lines - 1)
      val from = document.getLineStartOffset(firstLine)
      val to = if (hunk.afterEnd > hunk.afterStart) document.getLineEndOffset((hunk.afterEnd - 1).coerceIn(firstLine, lines - 1)) else from
      val tint = if (hunk.kind == Hunk.Kind.ADDED) ReviewColors.added else ReviewColors.changed
      val attributes = if (hunk.kind == Hunk.Kind.REMOVED) null else TextAttributes(null, tint, null, null, Font.PLAIN)
      val mark = markup.addRangeHighlighter(
        from, to, HighlighterLayer.SELECTION - 1, attributes, HighlighterTargetArea.LINES_IN_RANGE)
      mark.setErrorStripeMarkColor(when (hunk.kind) {
        Hunk.Kind.ADDED -> ReviewColors.accept
        Hunk.Kind.REMOVED -> ReviewColors.reject
        Hunk.Kind.CHANGED -> ReviewColors.current
      })
      mark.setErrorStripeTooltip(t("review.ruler"))
      highlighters.add(mark)

      val preview = EditHunks.preview(EditHunks.removedLines(review.before, hunk), MAX_REMOVED_LINES, tabSize)
      val block = HunkBlock(hunk, preview.lines, preview.hidden, current = index == review.place)
      // A hunk past the last line (text removed at the very end) hangs under that line, every other one sits above its first line
      val above = hunk.afterStart < lines
      val anchor = if (above) document.getLineStartOffset(hunk.afterStart) else document.textLength
      val properties = InlayProperties().relatesToPrecedingText(!above).showAbove(above).priority(INLAY_PRIORITY)
      editor.inlayModel.addBlockElement(anchor, properties, block)?.let { blocks.add(it) }
    }
    drawnStamp = document.modificationStamp
    drawnPlace = review.place
    drawnHunks = review.hunks
    showBar()
  }

  private fun clearMarks() {
    hovered = null
    highlighters.forEach { markup.removeHighlighter(it) }
    highlighters.clear()
    blocks.forEach { Disposer.dispose(it) }
    blocks.clear()
  }

  private class Hit(val inlay: Inlay<*>, val block: HunkBlock, val button: HunkBlock.Button)

  /** The button under the mouse, if the mouse is over one of ours */
  private fun hit(e: EditorMouseEvent): Hit? {
    val inlay = e.inlay ?: return null
    val block = inlay.renderer as? HunkBlock ?: return null
    val bounds = inlay.bounds ?: return null
    val point = e.mouseEvent.point
    val button = block.buttonAt(editor, point.x - bounds.x, point.y - bounds.y) ?: return null
    return Hit(inlay, block, button)
  }

  private fun hover(hit: Hit?) {
    if (hit?.block === hovered?.block && hit?.button == hovered?.button) return
    hovered?.let { it.block.hovered = null; it.inlay.repaint() }
    hovered = hit
    hit?.let { it.block.hovered = it.button; it.inlay.repaint() }
    editor.setCustomCursor(this, if (hit == null) null else Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
  }

  private fun warn(message: String) {
    NotificationGroupManager.getInstance().getNotificationGroup(VibeNotifications.AGENT)
      .createNotification(message, NotificationType.WARNING).notify(project)
  }

  private companion object {
    const val DEBOUNCE_MS = 120
    const val MAX_REMOVED_LINES = 200
    const val INLAY_PRIORITY = 0
  }
}
