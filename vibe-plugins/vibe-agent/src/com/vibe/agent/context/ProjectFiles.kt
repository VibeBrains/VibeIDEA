// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

/**
 * One way to walk the project's files, used by everything that walks them.
 *
 * There were three: the map of what is built, the documents panel and the semantic index, each with
 * its own filter — and they had already drifted. The documents panel did not honour `.vibe/ignore`,
 * so a file hidden from the agent was still listed there, which is the worst kind of inconsistency:
 * both behaviours look correct in isolation and the pair is a lie.
 */
object ProjectFiles {
  /** Directories nobody wants in any of the three answers. */
  private val SKIP = listOf(
    "node_modules", "dist", "build", "out", "target", "vendor", ".git", ".idea", "__pycache__", ".gradle",
  )

  /**
   * Relative path → content for files matching [extensions] (lowercase, without the dot).
   *
   * Honours `.vibe/ignore` always: a file the agent is forbidden to read has no business appearing
   * in a panel that offers it either.
   */
  fun read(project: Project, extensions: Set<String>, maxFileChars: Int = MAX_FILE_CHARS): Map<String, String> {
    val base = project.basePath ?: return emptyMap()
    val ignore = ProjectContextService.getInstance(project).ignore()
    val root = Path.of(base)
    return runCatching {
      Files.walk(root).use { stream -> stream.filter { Files.isRegularFile(it) }.toList() }
        .asSequence()
        .mapNotNull { path ->
          val relative = root.relativize(path).toString().replace('\\', '/')
          if (!matches(relative, extensions)) return@mapNotNull null
          if (ignore.isIgnored(relative)) return@mapNotNull null
          val text = runCatching { Files.readString(path) }.getOrNull() ?: return@mapNotNull null
          // A file larger than this is generated or data; reading it costs more than it says.
          if (text.length > maxFileChars) return@mapNotNull null
          relative to text
        }
        .toMap()
    }.getOrDefault(emptyMap())
  }

  /** Pure part: is this path one of ours, by directory and extension? */
  fun matches(relativePath: String, extensions: Set<String>): Boolean {
    val lower = relativePath.lowercase()
    if (SKIP.any { lower.startsWith("$it/") || lower.contains("/$it/") }) return false
    val extension = lower.substringAfterLast('.', "")
    return extension.isNotEmpty() && extension in extensions
  }

  const val MAX_FILE_CHARS = 400_000
}
