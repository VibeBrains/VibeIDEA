// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ingest

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

/** Запись входящего документа на диск: файл и обязательная строка индекса. */
@Service(Service.Level.PROJECT)
class IngestStore(private val project: Project) {
  /** @return путь записанного файла от корня проекта, или null, если писать не удалось. */
  fun write(source: Ingest.Source, indexHeader: String): String? {
    val base = project.basePath ?: return null
    val dir = Path.of(base, Ingest.FOLDER)
    return runCatching {
      Files.createDirectories(dir)
      val name = Ingest.fileName(source)
      Files.writeString(dir.resolve(name), Ingest.render(source))
      val index = dir.resolve(Ingest.INDEX)
      val existing = runCatching { Files.readString(index) }.getOrNull()
      Files.writeString(index, Ingest.appendToIndex(existing, source, indexHeader))
      Ingest.FOLDER + "/" + name
    }.getOrNull()
  }

  /** Записи индекса входящего; путь — от корня проекта, как ждёт агент. */
  fun entries(): List<com.vibe.agent.knowledge.Librarian.Entry> {
    val base = project.basePath ?: return emptyList()
    val index = Path.of(base, Ingest.FOLDER, Ingest.INDEX)
    val text = runCatching { Files.readString(index) }.getOrNull() ?: return emptyList()
    return com.vibe.agent.knowledge.Librarian.parseIndex(text)
      .map { it.copy(path = Ingest.FOLDER + "/" + it.path) }
  }

  /**
   * Документы в папке, которых нет в индексе.
   *
   * Такой документ не существует для всех, кроме того, кто его положил: библиотекарь ходит по
   * индексу, а не по папке. Файл появляется мимо индекса легко — его кладут руками.
   */
  fun orphans(): List<String> {
    val base = project.basePath ?: return emptyList()
    val dir = Path.of(base, Ingest.FOLDER)
    val files = runCatching {
      Files.list(dir).use { stream -> stream.map { it.fileName.toString() }.toList() }
    }.getOrDefault(emptyList())
    return CorpusIntegrity.orphans(files, entries().map { it.path }, Ingest.INDEX)
  }

  companion object {
    fun getInstance(project: Project): IngestStore = project.getService(IngestStore::class.java)
  }
}
