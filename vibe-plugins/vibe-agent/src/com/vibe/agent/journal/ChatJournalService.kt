// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.journal

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.util.xmlb.annotations.Tag
import com.vibe.agent.settings.VibeChatSettings
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The chat journal of one project: the service lines of the IDE that are not part of the conversation
 *
 * Any code of the project can write here, not only the chat panel: [append] is safe from any thread
 * The state lives in the workspace file of the project (`.idea/workspace.xml`), which is local and not versioned
 * So the journal survives a restart of the IDE and stays out of git and out of `.vibe/`
 * The platform saves it with the rest of the workspace, so there is no file handling here
 * A damaged state costs only itself: the journal opens empty
 */
@Service(Service.Level.PROJECT)
@State(name = "VibeChatJournal", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ChatJournalService : PersistentStateComponent<ChatJournalService.State> {
  /** The whole buffer as one JSON text: a damaged text is dropped here, in [ChatJournal.parse], and not by the XML reader */
  class State {
    @JvmField
    @Tag("entries")
    var entries: String = ""
  }

  private val lock = Any()
  private var current: List<JournalEntry> = emptyList()
  private val listeners = CopyOnWriteArrayList<() -> Unit>()

  /** How many entries to keep; replaced by a fixed number in tests, which run without the application */
  @Volatile
  internal var limitSource: () -> Int = { VibeChatSettings.journalMaxEntries }

  /** Oldest first; every change makes a new list, so an observer may compare by reference */
  val entries: List<JournalEntry> get() = synchronized(lock) { current }

  fun summary(): JournalSummary = ChatJournal.summarize(entries)

  /** A line of the IDE that is not part of the conversation; the same line twice in a row becomes one entry with a counter */
  fun append(input: JournalInput) = change { ChatJournal.append(it, input, System.currentTimeMillis(), limit()) }

  fun markAllRead() = change { ChatJournal.markAllRead(it) }

  fun clear() = change { if (it.isEmpty()) it else emptyList() }

  /** Cuts the buffer to the limit of the settings: the limit was lowered while the IDE was running */
  fun applyLimit() = change { ChatJournal.trim(it, limit()) }

  /** [listener] is called after every change, on the thread that made it; it is dropped with [parent] */
  fun addListener(parent: Disposable, listener: () -> Unit) {
    listeners.add(listener)
    Disposer.register(parent) { listeners.remove(listener) }
  }

  /** An empty journal is the default state, which the platform does not write into the workspace file */
  override fun getState(): State = State().apply {
    val kept = this@ChatJournalService.entries
    if (kept.isNotEmpty()) entries = ChatJournal.serialize(kept)
  }

  override fun loadState(loaded: State) {
    val restored = ChatJournal.parse(loaded.entries, limit())
    synchronized(lock) { current = restored }
  }

  private fun limit(): Int = limitSource()

  private fun change(transform: (List<JournalEntry>) -> List<JournalEntry>) {
    val changed = synchronized(lock) {
      val next = transform(current)
      (next !== current).also { current = next }
    }
    if (changed) listeners.forEach { it() }
  }

  companion object {
    fun getInstance(project: Project): ChatJournalService = project.getService(ChatJournalService::class.java)

    /** The limit was changed in the settings: every open project cuts its buffer now, not at the next line */
    fun applyLimitEverywhere() {
      ProjectManager.getInstance().openProjects.forEach { it.getServiceIfCreated(ChatJournalService::class.java)?.applyLimit() }
    }
  }
}
