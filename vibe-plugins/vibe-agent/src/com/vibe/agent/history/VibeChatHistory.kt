// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.history

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm
import com.vibe.agent.settings.VibeChatSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Pure in-memory chat-history core: the thread map plus every operation that
 * does not need the IntelliJ application (unit-testable as-is). NOT thread-safe —
 * [VibeChatHistory] guards every call with its own lock. The clock is injected
 * so tests control ordering and timestamps.
 */
internal class HistoryStore(val now: () -> String = { Instant.now().toString() }) {
  private val threads = LinkedHashMap<String, ChatThread>()

  /** Every thread, most recently modified first (stable for equal stamps). */
  fun all(): List<ChatThread> = threads.values.sortedByDescending { instantOf(it.lastModified) }

  /** Threads worth a history row: at least one message. */
  fun listed(): List<ChatThread> = all().filter { it.messages.isNotEmpty() }

  fun get(id: String): ChatThread? = threads[id]

  /** Used by persistence to seed the map on load. */
  fun put(thread: ChatThread) {
    threads[thread.id] = thread
  }

  fun create(workspaceId: String?, workspaceLabel: String?): ChatThread {
    val at = now()
    val thread = ChatThread(
      id = UUID.randomUUID().toString(),
      createdAt = at,
      lastModified = at,
      workspaceId = workspaceId,
      workspaceLabel = workspaceLabel,
      messages = emptyList(),
    )
    threads[thread.id] = thread
    return thread
  }

  /** Appends with the cap (trim marker on overflow) and bumps lastModified. */
  fun append(id: String, message: ChatMessageRecord, cap: Int): ChatThread? {
    val thread = threads[id] ?: return null
    val updated = ChatThread.appendCapped(thread, message, cap, now())
    threads[id] = updated
    return updated
  }

  /** Composer snapshot only — deliberately does not touch lastModified. */
  fun updateState(id: String, state: ThreadState): ChatThread? {
    val thread = threads[id] ?: return null
    val updated = thread.withState(state)
    threads[id] = updated
    return updated
  }

  /** Restamps the workspace; deliberately does not touch lastModified. */
  fun reassign(id: String, workspaceId: String?, workspaceLabel: String?): ChatThread? {
    val thread = threads[id] ?: return null
    val updated = thread.withWorkspace(workspaceId, workspaceLabel)
    threads[id] = updated
    return updated
  }

  /** Restamps every untagged thread onto the given workspace; returns how many. */
  fun claimUntagged(workspaceId: String, workspaceLabel: String?): Int {
    var count = 0
    for (entry in threads.entries) {
      if (entry.value.workspaceId == null) {
        entry.setValue(entry.value.withWorkspace(workspaceId, workspaceLabel))
        count++
      }
    }
    return count
  }

  /** Deep copy under a new id; SAME lastModified so the copy sits next to the original. */
  fun duplicate(id: String): ChatThread? {
    val source = threads[id] ?: return null
    val copy = ChatThread(
      id = UUID.randomUUID().toString(),
      createdAt = source.createdAt,
      lastModified = source.lastModified,
      workspaceId = source.workspaceId,
      workspaceLabel = source.workspaceLabel,
      messages = source.messages.map { m ->
        ChatMessageRecord(
          role = m.role,
          text = m.text,
          images = m.images.map { StoredImage(it.name, it.mimeType, it.base64) },
          at = m.at,
          wireText = m.wireText,
          pinned = m.pinned,
        )
      },
      state = ThreadState(targetId = source.state.targetId),
    )
    threads[copy.id] = copy
    return copy
  }

  fun delete(id: String): Boolean = threads.remove(id) != null

  /** Toggles the pin on one message; returns the new state, or null when there is no such message. */
  fun setPinned(id: String, index: Int, pinned: Boolean): Boolean? {
    val thread = threads[id] ?: return null
    if (index !in thread.messages.indices) return null
    val updated = thread.withMessages(thread.messages.toMutableList().also {
      it[index] = it[index].withPinned(pinned)
    })
    threads[id] = updated
    return pinned
  }

  /**
   * A new thread carrying the conversation up to [index] — the original stays untouched, which is
   * the whole point: a branch is for trying another path, not for rewriting the walked one.
   */
  fun branch(id: String, index: Int): ChatThread? {
    val at = now()
    val source = threads[id] ?: return null
    val kept = ChatThread.branch(source.messages, index)
    if (kept.isEmpty()) return null
    val copy = ChatThread(
      id = UUID.randomUUID().toString(),
      createdAt = at,
      lastModified = at,
      workspaceId = source.workspaceId,
      workspaceLabel = source.workspaceLabel,
      messages = kept.map { m ->
        ChatMessageRecord(m.role, m.text, m.images.map { StoredImage(it.name, it.mimeType, it.base64) },
                          m.at, m.wireText, m.pinned)
      },
      state = ThreadState(targetId = source.state.targetId),
    )
    threads[copy.id] = copy
    return copy
  }

  /** Fills in the wire text of the LAST user message once it is known (context loads async). */
  fun setLastUserWireText(id: String, wireText: String?): ChatThread? {
    val thread = threads[id] ?: return null
    val index = thread.messages.indexOfLast { it.role == Role.USER }
    if (index < 0 || wireText == null) return null
    val m = thread.messages[index]
    if (m.wireText != null) return null
    val updated = thread.withMessages(thread.messages.toMutableList().also {
      it[index] = ChatMessageRecord(m.role, m.text, m.images, m.at, wireText, m.pinned)
    })
    threads[id] = updated
    return updated
  }

  /** Drops images from the LAST user message (a provider rejected the payload — do not poison every retry). */
  fun dropImagesFromLastUser(id: String): Boolean {
    val thread = threads[id] ?: return false
    val index = thread.messages.indexOfLast { it.role == Role.USER }
    if (index < 0 || thread.messages[index].images.isEmpty()) return false
    val m = thread.messages[index]
    threads[id] = thread.withMessages(thread.messages.toMutableList().also {
      it[index] = ChatMessageRecord(m.role, m.text, emptyList(), m.at, m.wireText)
    })
    return true
  }

  /** Empty threads are invisible in every list; keeping them across restarts only grows the file. */
  fun purgeEmpty(): Int {
    val empty = threads.values.filter { it.messages.isEmpty() }.map { it.id }
    empty.forEach { threads.remove(it) }
    return empty.size
  }

  /** Threads explicitly tagged with a DIFFERENT workspace; untagged never counted. */
  fun otherProjectsCount(currentWorkspaceId: String?): Int =
    threads.values.count { it.workspaceId != null && it.workspaceId != currentWorkspaceId }

  /** A thread belongs to the current scope when it is its own or untagged. */
  fun matchesWorkspace(thread: ChatThread, currentWorkspaceId: String?): Boolean =
    thread.workspaceId == null || thread.workspaceId == currentWorkspaceId

  private fun instantOf(value: String): Instant =
    runCatching { Instant.parse(value) }.getOrDefault(Instant.EPOCH)
}

/**
 * Application-wide chat history: wraps [HistoryStore] with debounced JSON
 * persistence (single file `<config>/vibe/chatHistory.json`), EDT listeners and
 * the persisted show-all-projects scope toggle. Mutations may come from EDT and
 * pooled threads; the store is guarded by [lock], listeners always fire on EDT.
 */
@Service(Service.Level.APP)
class VibeChatHistory : Disposable {
  private val lock = Any()
  private val store = HistoryStore()
  private val listeners = CopyOnWriteArrayList<Runnable>()
  private val saveAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

  /** Guarded by [lock]; set on mutation, cleared when a snapshot is taken for writing. */
  private var dirty = false

  init {
    load()
  }

  fun all(): List<ChatThread> = synchronized(lock) { store.all() }

  fun listed(): List<ChatThread> = synchronized(lock) { store.listed() }

  fun get(id: String): ChatThread? = synchronized(lock) { store.get(id) }

  fun create(workspaceId: String?, workspaceLabel: String?): ChatThread {
    val thread = synchronized(lock) { store.create(workspaceId, workspaceLabel) }
    mutated()
    return thread
  }

  fun append(id: String, message: ChatMessageRecord) {
    val cap = VibeChatSettings.maxMessagesPerThread
    val changed = synchronized(lock) { store.append(id, message, cap) != null }
    if (changed) mutated()
  }

  fun updateState(id: String, state: ThreadState) {
    val changed = synchronized(lock) { store.updateState(id, state) != null }
    if (changed) mutated()
  }

  fun reassign(id: String, workspaceId: String?, workspaceLabel: String?) {
    val changed = synchronized(lock) { store.reassign(id, workspaceId, workspaceLabel) != null }
    if (changed) mutated()
  }

  fun claimUntagged(workspaceId: String, workspaceLabel: String?): Int {
    val count = synchronized(lock) { store.claimUntagged(workspaceId, workspaceLabel) }
    if (count > 0) mutated()
    return count
  }

  fun duplicate(id: String): ChatThread? {
    val copy = synchronized(lock) { store.duplicate(id) }
    if (copy != null) mutated()
    return copy
  }

  fun delete(id: String) {
    val changed = synchronized(lock) { store.delete(id) }
    if (changed) mutated()
  }

  fun setPinned(id: String, index: Int, pinned: Boolean): Boolean? {
    val result = synchronized(lock) { store.setPinned(id, index, pinned) }
    if (result != null) mutated()
    return result
  }

  fun branch(id: String, index: Int): ChatThread? {
    val copy = synchronized(lock) { store.branch(id, index) }
    if (copy != null) mutated()
    return copy
  }

  fun setLastUserWireText(id: String, wireText: String?) {
    val changed = synchronized(lock) { store.setLastUserWireText(id, wireText) != null }
    if (changed) scheduleSave()
  }

  fun dropImagesFromLastUser(id: String): Boolean {
    val changed = synchronized(lock) { store.dropImagesFromLastUser(id) }
    if (changed) mutated()
    return changed
  }

  /**
   * In-memory turn ownership: the store is app-wide, so the same (untagged) thread can be
   * open in two projects — only one panel may run a turn in it at a time. Never persisted,
   * so a crash cannot leave a stale lock.
   */
  private val activeTurns = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

  fun tryBeginTurn(threadId: String): Boolean = activeTurns.add(threadId)

  fun endTurn(threadId: String) {
    activeTurns.remove(threadId)
  }

  fun otherProjectsCount(currentWorkspaceId: String?): Int =
    synchronized(lock) { store.otherProjectsCount(currentWorkspaceId) }

  fun matchesWorkspace(thread: ChatThread, currentWorkspaceId: String?): Boolean =
    store.matchesWorkspace(thread, currentWorkspaceId)

  /** App-wide show-all-projects toggle; persisted, change notifies listeners. */
  var showAllProjects: Boolean
    get() = PropertiesComponent.getInstance().getBoolean(KEY_SHOW_ALL_PROJECTS, SHOW_ALL_DEFAULT)
    set(value) {
      if (value == showAllProjects) return
      PropertiesComponent.getInstance().setValue(KEY_SHOW_ALL_PROJECTS, value, SHOW_ALL_DEFAULT)
      fireListeners()
    }

  /** Fired on EDT after any mutation or scope change; auto-removed with [parent]. */
  fun addListener(parent: Disposable, listener: Runnable) {
    listeners.add(listener)
    Disposer.register(parent, Disposable { listeners.remove(listener) })
  }

  /** Forces a pending debounced save to disk right now (synchronously). */
  fun flush() {
    saveAlarm.cancelAllRequests()
    saveNow()
  }

  override fun dispose() {
    flush()
  }

  private fun mutated() {
    scheduleSave()
    fireListeners()
  }

  private fun fireListeners() {
    ApplicationManager.getApplication().invokeLater(
      { listeners.forEach(Runnable::run) },
      ModalityState.any(),
    )
  }

  private fun scheduleSave() {
    synchronized(lock) { dirty = true }
    // Re-arming the single request collapses a burst of mutations into one write.
    saveAlarm.cancelAllRequests()
    saveAlarm.addRequest(::saveNow, SAVE_DELAY_MS)
  }

  private fun saveNow() {
    val snapshot = synchronized(lock) {
      if (!dirty) return
      dirty = false
      store.all()
    }
    val file = storeFile()
    try {
      val root = buildJsonObject {
        put("threads", JsonArray(snapshot.map(ChatTranscriptCodec::toJson)))
      }
      Files.createDirectories(file.parent)
      // Atomic write: temp file in the same directory, then move over the target.
      // Unique per write: two racing writers must not interleave into one temp file.
      val tmp = Files.createTempFile(file.parent, file.fileName.toString(), TMP_SUFFIX)
      Files.writeString(tmp, root.toString())
      try {
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      }
      catch (ignored: AtomicMoveNotSupportedException) {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
      }
    }
    catch (e: Exception) {
      LOG.warn("Failed to save chat history to $file", e)
      // Keep the data pending so the next mutation retries the write.
      synchronized(lock) { dirty = true }
    }
  }

  /** Tolerant load: unreadable file → empty store; a broken thread entry is skipped by the codec. */
  private fun load() {
    val file = storeFile()
    if (!Files.isRegularFile(file)) return
    try {
      val root = Json.parseToJsonElement(Files.readString(file)) as? JsonObject
      val entries = root?.get("threads") as? JsonArray
      if (entries == null) {
        LOG.warn("Chat history file $file has no 'threads' array — starting with empty history")
        return
      }
      synchronized(lock) {
        for (entry in entries) {
          val thread = ChatTranscriptCodec.fromJson(entry)
          if (thread == null) {
            LOG.warn("Chat history: skipped a broken thread entry in $file")
            continue
          }
          store.put(thread)
        }
        val purged = store.purgeEmpty()
        if (purged > 0) {
          LOG.info("Chat history: dropped $purged empty threads")
          dirty = true
        }
      }
    }
    catch (e: Exception) {
      // Never overwrite a corrupt file silently: park it next to the store for manual recovery.
      val backup = file.resolveSibling(file.fileName.toString() + BROKEN_SUFFIX)
      runCatching { Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING) }
      LOG.warn("Failed to load chat history from $file — file moved to $backup, starting with empty history", e)
    }
  }

  private fun storeFile(): Path = Path.of(PathManager.getConfigPath(), STORE_DIR, STORE_FILE_NAME)

  companion object {
    private val LOG = Logger.getInstance(VibeChatHistory::class.java)
    private const val SAVE_DELAY_MS = 1500
    private const val STORE_DIR = "vibe"
    private const val STORE_FILE_NAME = "chatHistory.json"
    private const val TMP_SUFFIX = ".tmp"
    private const val BROKEN_SUFFIX = ".broken"
    private const val KEY_SHOW_ALL_PROJECTS = "vibe.history.showAllProjects"
    private const val SHOW_ALL_DEFAULT = false

    @JvmStatic
    fun getInstance(): VibeChatHistory =
      ApplicationManager.getApplication().getService(VibeChatHistory::class.java)
  }
}
