// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
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
    if (skips(relativePath)) return false
    val extension = relativePath.lowercase().substringAfterLast('.', "")
    return extension.isNotEmpty() && extension in extensions
  }

  /**
   * Pure part: does this path lie in a directory nobody wants — a file inside one, or the directory itself?
   *
   * The directory itself counts because a walk prunes subtrees by the path of the directory it is about to enter,
   * and without that case every walk would descend into `node_modules` and find nothing to skip.
   */
  fun skips(relativePath: String): Boolean {
    val segments = relativePath.lowercase().replace('\\', '/').split('/')
    // Every segment counts, including the last: a walk prunes by the directory it is about to enter,
    // and that path (`app/build`) has no child to match against.
    return segments.any { it in SKIP }
  }

  const val MAX_FILE_CHARS = 400_000
}
