// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.knowledge

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

/**
 * Finds the project's knowledge index and keeps it in memory until the file changes.
 *
 * The location is not fixed by us: projects put their notes where they put them, so the known
 * shapes are tried in order and the first one that exists wins. A project without notes costs one
 * `exists` check per turn and says nothing — the librarian must be invisible when there is nothing
 * to say.
 */
@Service(Service.Level.PROJECT)
class KnowledgeIndex(private val project: Project) {
  private data class Cached(val entries: List<Librarian.Entry>, val root: String, val stamp: Long)

  @Volatile private var cached: Cached? = null

  fun entries(): List<Librarian.Entry> = load()?.entries.orEmpty()

  /** Paths in the index are relative to the index file; the prompt needs them from the project root. */
  fun relativeTo(entry: Librarian.Entry): String {
    val root = load()?.root ?: return entry.path
    return "$root/${entry.path}"
  }

  private fun load(): Cached? {
    val base = project.basePath ?: return null
    for (candidate in CANDIDATES) {
      val file = Path.of(base, candidate)
      if (!Files.isRegularFile(file)) continue
      val stamp = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)
      val current = cached
      if (current != null && current.root == candidate.substringBeforeLast('/') && current.stamp == stamp) return current
      val text = runCatching { Files.readString(file) }.getOrNull() ?: return null
      val loaded = Cached(Librarian.parseIndex(text), candidate.substringBeforeLast('/'), stamp)
      cached = loaded
      return loaded
    }
    return null
  }

  companion object {
    /** Where projects actually keep their notes, most specific first. */
    private val CANDIDATES = listOf(
      "docs/vibe/knowledge/README.md",
      "docs/knowledge/README.md",
      "knowledge/README.md",
      ".vibe/knowledge/README.md",
    )

    fun getInstance(project: Project): KnowledgeIndex = project.getService(KnowledgeIndex::class.java)
  }
}
