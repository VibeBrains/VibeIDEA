// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.decisions

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.vibe.agent.knowledge.Librarian
import java.nio.file.Files
import java.nio.file.Path

/**
 * Решения проекта на диске: чтение индекса и запись новой записи.
 *
 * Папку не навязываем: проекты уже держат решения по-своему (`docs/decisions`, `decisions`,
 * `docs/adr`), и требовать переезда ради нашей фичи — верный способ, чтобы фичей не пользовались.
 * Пишем в первую существующую, а если нет ни одной — создаём умолчание.
 *
 * Индекс читается тем же разбором, что и база знаний: одна дисциплина, один парсер, один гейт
 * связности.
 */
@Service(Service.Level.PROJECT)
class DecisionStore(private val project: Project) {
  private data class Cached(val entries: List<Librarian.Entry>, val folder: String, val stamp: Long)

  @Volatile private var cached: Cached? = null

  /** Записи индекса решений; путь в них — от корня проекта, как ждёт агент. */
  fun entries(): List<Librarian.Entry> {
    val loaded = load() ?: return emptyList()
    return loaded.entries.map { it.copy(path = loaded.folder + "/" + it.path) }
  }

  /** Папка решений: существующая или умолчание. */
  fun folder(): String {
    val base = project.basePath ?: return DecisionRecord.FOLDER
    return CANDIDATES.firstOrNull { Files.isDirectory(Path.of(base, it)) } ?: DecisionRecord.FOLDER
  }

  fun nextNumber(): Int {
    val base = project.basePath ?: return 1
    val dir = Path.of(base, folder())
    val names = runCatching {
      Files.list(dir).use { stream -> stream.map { it.fileName.toString() }.toList() }
    }.getOrDefault(emptyList())
    return DecisionRecord.nextNumber(names)
  }

  /**
   * Пишет решение и строку индекса.
   *
   * Строка индекса обязательна и пишется здесь же: запись без строки — запись, которой не
   * существует, и «допишу потом» этого правила не переживает.
   *
   * @return путь записанного файла от корня проекта, или null, если писать не удалось.
   */
  fun write(decision: DecisionRecord.Decision, indexHeader: String): String? {
    val base = project.basePath ?: return null
    val folder = folder()
    val dir = Path.of(base, folder)
    return runCatching {
      Files.createDirectories(dir)
      val name = DecisionRecord.fileName(decision)
      Files.writeString(dir.resolve(name), DecisionRecord.render(decision))
      val index = dir.resolve(DecisionRecord.INDEX)
      val existing = runCatching { Files.readString(index) }.getOrNull()
      Files.writeString(index, DecisionRecord.appendToIndex(existing, decision, indexHeader))
      cached = null
      "$folder/$name"
    }.getOrNull()
  }

  private fun load(): Cached? {
    val base = project.basePath ?: return null
    for (candidate in CANDIDATES) {
      val file = Path.of(base, candidate, DecisionRecord.INDEX)
      if (!Files.isRegularFile(file)) continue
      val stamp = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)
      val current = cached
      if (current != null && current.folder == candidate && current.stamp == stamp) return current
      val text = runCatching { Files.readString(file) }.getOrNull() ?: return null
      val loaded = Cached(Librarian.parseIndex(text), candidate, stamp)
      cached = loaded
      return loaded
    }
    return null
  }

  companion object {
    /** Где проекты держат решения, самое привычное первым. */
    private val CANDIDATES = listOf(
      DecisionRecord.FOLDER,
      "docs/vibe/decisions",
      "docs/adr",
      "decisions",
      ".vibe/decisions",
    )

    fun getInstance(project: Project): DecisionStore = project.getService(DecisionStore::class.java)
  }
}
