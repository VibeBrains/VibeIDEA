// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.review

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.vibe.agent.mcp.AgentEditJournal
import java.io.File

/**
 * Puts the review into the editors of the files the agent changed, and moves the reader from one file to the next
 *
 * It listens to the journal (from whatever thread the agent writes on) and to the editors being opened, and keeps one
 * [ReviewSession] per editor whose file has an entry; a session goes when its entry does, or when its editor closes
 */
@Service(Service.Level.PROJECT)
class AgentReviewService(private val project: Project) : Disposable {
  private val journal = AgentEditJournal.getInstance(project)

  /** By the editor, so a split of one file has a session in each half; touched on the EDT only */
  private val sessions = LinkedHashMap<FileEditor, ReviewSession>()
  private val subscription: AutoCloseable = journal.subscribe { change -> later { onJournal(change) } }

  /** Editors that were open before the service started have heard no `fileOpened` */
  fun attachOpenEditors() = later { FileEditorManager.getInstance(project).allEditors.forEach { attach(it) } }

  /** Opens the review in [fileEditor] if its file is in the journal and the editor is a text one */
  fun attach(fileEditor: FileEditor) {
    if (sessions.containsKey(fileEditor) || fileEditor !is TextEditor) return
    val file = fileEditor.file ?: return
    val entry = entryFor(file) ?: return
    val editor = fileEditor.editor as? EditorEx ?: return
    if (editor.isDisposed) return
    val session = ReviewSession(project, fileEditor, editor, file, entry.path, this)
    sessions[fileEditor] = session
    // The editor goes first sometimes (a closed tab); the session must not outlive the markup it drew on
    EditorUtil.disposeWithEditor(editor, session)
    if (!session.start()) Disposer.dispose(session)
  }

  /** Drops the sessions whose editors are gone */
  fun sweep() {
    sessions.entries.filter { (fileEditor, session) -> !fileEditor.isValid || session.editor.isDisposed }
      .forEach { Disposer.dispose(it.value) }
  }

  fun sessionFor(editor: Editor): ReviewSession? = sessions.values.firstOrNull { it.editor === editor }

  fun forget(session: ReviewSession) {
    sessions.remove(session.fileEditor)
  }

  /** The files the reader can go through, in the order the agent touched them; a file that no longer exists is not one of them */
  private fun files(): List<String> = journal.all().map { it.path }.filter { File(it).isFile }

  fun fileCount(): Int = files().size

  /** The place of [path] among the files, or -1 */
  fun indexOf(path: String): Int {
    val key = ReviewPaths.key(path)
    return files().indexOfFirst { ReviewPaths.key(it) == key }
  }

  /** Opens the file [offset] steps from [path] in the list, round the ends */
  fun openNeighbour(path: String, offset: Int) {
    val all = files()
    if (all.isEmpty()) return
    val from = indexOf(path).coerceAtLeast(0)
    open(all[Math.floorMod(from + offset, all.size)])
  }

  /** Where the reader goes once the file at [position] has been settled: the file that followed it, or the one before */
  fun openAfterResolve(position: Int) {
    // A file that was not in the list (gone from disk, say) has no follower to hand the place to
    if (position < 0) return
    val all = files()
    val next = HunkNavigation.afterResolve(position, all.size) ?: return
    open(all[next])
  }

  /**
   * Opens [path] at the hunk the reader is on in it, or at its first hunk
   * The editor does the scrolling: a file opened just now has no size yet for us to measure
   */
  fun open(path: String) {
    val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(path)) ?: return
    val line = sessions.values.firstOrNull { ReviewPaths.key(it.journalPath) == ReviewPaths.key(path) }?.currentLine()
               ?: firstHunkLine(path, file)
    OpenFileDescriptor(project, file, line, 0).navigate(true)
  }

  override fun dispose() {
    subscription.close()
    sessions.values.toList().forEach { Disposer.dispose(it) }
    sessions.clear()
  }

  private fun firstHunkLine(path: String, file: VirtualFile): Int {
    val entry = journal.all().firstOrNull { it.path == path } ?: return 0
    val document = FileDocumentManager.getInstance().getDocument(file) ?: return 0
    val hunk = EditHunks.between(entry.before.orEmpty(), document.text).firstOrNull() ?: return 0
    return hunk.afterStart.coerceIn(0, document.lineCount - 1)
  }

  private fun entryFor(file: VirtualFile): AgentEditJournal.Entry? {
    val key = ReviewPaths.key(file.path)
    return journal.all().firstOrNull { ReviewPaths.key(it.path) == key }
  }

  private fun onJournal(change: AgentEditJournal.Change) {
    val key = ReviewPaths.key(change.path)
    val own = { session: ReviewSession -> ReviewPaths.key(session.journalPath) == key }
    when (change) {
      is AgentEditJournal.Change.Removed -> sessions.values.filter(own).forEach { Disposer.dispose(it) }
      is AgentEditJournal.Change.Recorded -> {
        // The file may be open and not yet reviewed: this is the write that gives it an entry
        attachOpenEditors()
        sessions.values.filter(own).forEach { it.onJournal(change) }
      }
      is AgentEditJournal.Change.Resolved -> sessions.values.filter(own).forEach { it.onJournal(change) }
    }
    // Other files came or went, so every strip's "file K of L" is stale
    sessions.values.toList().forEach { it.showBar() }
  }

  /** The journal is written from the agent's threads; the editors are not theirs to touch */
  private fun later(action: () -> Unit) {
    ApplicationManager.getApplication().invokeLater(action, project.disposed)
  }

  companion object {
    fun getInstance(project: Project): AgentReviewService = project.service()
  }
}

/** Opens the review in an editor when its file is opened or brought to the front, and sweeps the sessions of closed ones */
class ReviewEditorListener : FileEditorManagerListener {
  override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
    val reviews = AgentReviewService.getInstance(source.project)
    source.getAllEditors(file).forEach { reviews.attach(it) }
  }

  override fun fileClosed(source: FileEditorManager, file: VirtualFile) = AgentReviewService.getInstance(source.project).sweep()

  override fun selectionChanged(event: FileEditorManagerEvent) {
    event.newEditor?.let { AgentReviewService.getInstance(event.manager.project).attach(it) }
  }
}

/** Starts the service at project open, so the journal has a listener from the first write of the agent */
class ReviewStartup : ProjectActivity {
  override suspend fun execute(project: Project) {
    AgentReviewService.getInstance(project).attachOpenEditors()
  }
}
