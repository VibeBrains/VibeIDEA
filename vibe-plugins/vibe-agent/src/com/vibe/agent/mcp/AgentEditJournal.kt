// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.mcp

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Что агент изменил и каким файл был ДО этого.
 *
 * Зачем хранить прежний текст: без него «отклонить» невозможно, а решение «принимать или нет»
 * человек принимает вслепую. Откат целиком по чекпоинту (`/undo`) есть давно, но он про весь ход
 * сразу и задним числом — а спрашивают обычно про один файл из пяти.
 *
 * Снимок берётся ОДИН раз на файл — при первом касании. Второе и третье изменение того же файла
 * агентом — это продолжение той же работы, и откатывать надо к состоянию до неё, а не к предыдущему
 * шагу самого агента.
 *
 * Правки человека, сделанные поверх, снимок не перезаписывают и не отменяются молча: перед откатом
 * содержимое сверяется с тем, что мы записали как «после», и разошедшийся файл возвращает отказ —
 * стереть чужую работу хуже, чем не откатить.
 *
 * Чистый по построению: файловая система внутри, платформа — только в обновлении VFS, и оно
 * передаётся снаружи. Иначе правило «отклонить» проверялось бы только запуском IDE, а цена ошибки
 * здесь — стёртая чужая работа.
 */
class AgentEditJournal(private val onRefresh: (File) -> Unit = {}) {
  data class Entry(val path: String, val before: String?, val after: String)

  private val entries = ConcurrentHashMap<String, Entry>()

  private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Change) -> Unit>()

  /** Порядок появления сохраняется: список читается сверху вниз, как шла работа. */
  private val order = java.util.concurrent.CopyOnWriteArrayList<String>()

  fun record(path: String, before: String?, after: String) {
    val existing = entries[path]
    if (existing == null) {
      order.add(path)
      entries[path] = Entry(path, before, after)
    }
    else {
      // Прежний снимок сохраняется, обновляется только «после»: откат ведёт к состоянию до начала.
      entries[path] = existing.copy(after = after)
    }
    announce(Change.Recorded(path, after))
  }

  fun all(): List<Entry> = order.mapNotNull { entries[it] }

  fun isEmpty(): Boolean = entries.isEmpty()

  fun size(): Int = entries.size

  /** Принять: изменение остаётся, из списка уходит. */
  fun accept(path: String) {
    val removed = entries.remove(path)
    order.remove(path)
    if (removed != null) announce(Change.Removed(path))
  }

  fun acceptAll() {
    val paths = order.toList()
    entries.clear()
    order.clear()
    paths.forEach { announce(Change.Removed(it)) }
  }

  /**
   * A person accepted part of the file's changes in the editor: the baseline moves to [before], which has those lines in it
   *
   * The entry leaves the journal when nothing is left to decide ([settled]), exactly as with [accept]
   * The text the agent wrote is not touched: an edit made on top of it stays visible to the drift check of [reject]
   */
  fun rebase(path: String, before: String, settled: Boolean) {
    if (settled) {
      accept(path)
      return
    }
    if (entries.computeIfPresent(path) { _, entry -> entry.copy(before = before) } != null) announce(Change.Resolved(path))
  }

  /**
   * A person rejected part of the file's changes in the editor: the document now holds a text without those lines
   *
   * The recorded text moves to [after] unless it is null: the caller passes null when the file had been edited by a person already,
   * so that edit still reads as drift to the check in [reject]
   * The entry leaves the journal when nothing is left to decide ([settled]), exactly as with [accept]
   */
  fun follow(path: String, after: String?, settled: Boolean) {
    if (settled) {
      accept(path)
      return
    }
    val kept = entries.computeIfPresent(path) { _, entry -> if (after == null) entry else entry.copy(after = after) }
    if (kept != null) announce(Change.Resolved(path))
  }

  /** What happened to the journal, for whoever shows the entries: the editor marks, the strip over the chat input */
  sealed interface Change {
    val path: String

    /** The agent wrote the file ([after] is what it wrote) */
    data class Recorded(override val path: String, val after: String) : Change

    /** A person accepted or rejected a part of the file's changes */
    data class Resolved(override val path: String) : Change

    /** The entry left the journal: accepted or rejected as a whole */
    data class Removed(override val path: String) : Change
  }

  /** Listens for [Change]s on whatever thread made them; close the result to stop */
  fun subscribe(listener: (Change) -> Unit): AutoCloseable {
    listeners.add(listener)
    return AutoCloseable { listeners.remove(listener) }
  }

  private fun announce(change: Change) {
    for (listener in listeners) {
      // A subscriber that fails must not fail the write that was being recorded: the file is already changed
      try {
        listener(change)
      }
      catch (_: Exception) {
      }
    }
  }

  /** Результат отката — словами, потому что каждый отказ здесь означает разное. */
  sealed interface Revert {
    data object Done : Revert
    /** Файл изменён после агента: откат стёр бы чужую работу. */
    data object Drifted : Revert
    data class Failed(val reason: String) : Revert
  }

  /**
   * Отклонить: вернуть файл к состоянию до работы агента.
   *
   * Созданный агентом файл (снимка «до» нет) удаляется: вернуть его «как было» значит убрать.
   */
  fun reject(path: String): Revert {
    val entry = entries[path] ?: return Revert.Failed(path)
    val file = File(path)
    val current = runCatching { if (file.isFile) file.readText() else null }.getOrNull()
    if (current != null && current != entry.after) return Revert.Drifted
    return try {
      if (entry.before == null) file.delete() else file.writeText(entry.before)
      onRefresh(file)
      accept(path)
      Revert.Done
    }
    catch (e: Exception) {
      Revert.Failed(e.message ?: e.javaClass.simpleName)
    }
  }

  companion object {
    fun getInstance(project: Project): AgentEditJournal = project.service<Holder>().journal
  }

  /** Держатель на проект: сам журнал ничего не знает ни о проекте, ни о платформе. */
  @Service(Service.Level.PROJECT)
  class Holder {
    val journal: AgentEditJournal = AgentEditJournal { file ->
      LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
    }
  }
}
