// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

/**
 * The project's answer to "what must you read, and what must you not touch": rules, reference
 * folders and the ignore list, read from disk and cached until the files change.
 *
 * Caching is by modification time rather than by a timer: a rule edited in the editor must reach
 * the very next turn, and rereading four files on every keystroke would be the other extreme.
 */
@Service(Service.Level.PROJECT)
class ProjectContextService(private val project: Project) {
  private val log = logger<ProjectContextService>()

  private data class Cached<T>(val value: T, val stamp: Long)

  @Volatile private var rules: Cached<List<ProjectRules.Rule>>? = null
  @Volatile private var ignore: Cached<VibeIgnore>? = null

  /** Absolute folders outside the project the agent may read; empty by default. */
  fun referenceFolders(): List<String> =
    PropertiesComponent.getInstance(project).getValue(KEY_REFERENCE_FOLDERS).orEmpty()
      .split(PATH_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

  fun setReferenceFolders(folders: List<String>) {
    PropertiesComponent.getInstance(project).setValue(KEY_REFERENCE_FOLDERS, folders.joinToString(PATH_SEPARATOR))
  }

  /** Paths inside the project the agent may read but never write. */
  fun sourceFolders(): List<String> =
    PropertiesComponent.getInstance(project).getValue(KEY_SOURCE_FOLDERS).orEmpty()
      .split(PATH_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

  fun setSourceFolders(folders: List<String>) {
    PropertiesComponent.getInstance(project).setValue(KEY_SOURCE_FOLDERS, folders.joinToString(PATH_SEPARATOR))
  }

  fun roots(): AccessPolicy.Roots = AccessPolicy.Roots(
    projectBase = project.basePath,
    referenceFolders = referenceFolders(),
    sourceFolders = sourceFolders(),
    ignore = ignore(),
  )

  fun ignore(): VibeIgnore {
    val base = project.basePath ?: return VibeIgnore.EMPTY
    val file = Path.of(base, VibeIgnore.FILE)
    val stamp = stampOf(file)
    ignore?.let { if (it.stamp == stamp) return it.value }
    val parsed = if (stamp == 0L) VibeIgnore.EMPTY
                 else runCatching { VibeIgnore.parse(Files.readString(file)) }.getOrElse {
                   log.warn("$file could not be read: ${it.message}")
                   VibeIgnore.EMPTY
                 }
    ignore = Cached(parsed, stamp)
    return parsed
  }

  /**
   * Every rule the project declares, in a stable order: `.cursor/rules` first (declaration order
   * by file name), the legacy `.cursorrules` last, as a rule that always applies — that is what
   * the file meant before the folder format existed.
   */
  /**
   * Rules for this turn: the root ones plus those of the folders the touched files live in.
   *
   * Nested rules are read on demand rather than cached: the set of folders changes with every turn,
   * and a cache keyed by it would be a cache that never hits. There are at most a handful of them,
   * each a few kilobytes.
   */
  fun rules(touchedPaths: List<String>): List<ProjectRules.Rule> {
    val base = project.basePath ?: return emptyList()
    val root = rules()
    val nested = ArrayList<ProjectRules.Rule>()
    for (dir in ProjectRules.ruleDirsFor(touchedPaths)) {
      if (dir.isEmpty()) continue
      val rulesDir = Path.of(base, dir, ProjectRules.RULES_DIR)
      if (!Files.isDirectory(rulesDir)) continue
      val files = runCatching { Files.list(rulesDir).use { stream -> stream.toList() } }.getOrDefault(emptyList())
      for (path in files.sortedBy { it.fileName.toString() }) {
        if (!path.fileName.toString().endsWith(ProjectRules.RULE_EXTENSION)) continue
        val text = runCatching { Files.readString(path) }.getOrNull() ?: continue
        nested.add(ProjectRules.parse(path.fileName.toString().removeSuffix(ProjectRules.RULE_EXTENSION), text, dir))
      }
    }
    return if (nested.isEmpty()) root else ProjectRules.nearestWins(root + nested)
  }

  fun rules(): List<ProjectRules.Rule> {
    val base = project.basePath ?: return emptyList()
    val dir = Path.of(base, ProjectRules.RULES_DIR)
    val legacy = Path.of(base, ProjectRules.LEGACY_FILE)
    val stamp = stampOf(dir) * 31 + stampOf(legacy) + fileStamps(dir)
    rules?.let { if (it.stamp == stamp) return it.value }

    val loaded = ArrayList<ProjectRules.Rule>()
    if (Files.isDirectory(dir)) {
      val files = runCatching { Files.list(dir).use { stream -> stream.toList() } }.getOrDefault(emptyList())
      for (path in files.sortedBy { it.fileName.toString() }) {
        if (!path.fileName.toString().endsWith(ProjectRules.RULE_EXTENSION)) continue
        val text = runCatching { Files.readString(path) }.getOrNull() ?: continue
        loaded.add(ProjectRules.parse(path.fileName.toString().removeSuffix(ProjectRules.RULE_EXTENSION), text))
      }
    }
    if (Files.isRegularFile(legacy)) {
      runCatching { Files.readString(legacy) }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
        loaded.add(ProjectRules.Rule(ProjectRules.LEGACY_FILE, dir = "", description = null, globs = emptyList(),
                                     alwaysApply = true, body = it.trim()))
      }
    }
    rules = Cached(loaded, stamp)
    return loaded
  }

  private fun fileStamps(dir: Path): Long {
    if (!Files.isDirectory(dir)) return 0L
    return runCatching {
      Files.list(dir).use { stream -> stream.toList() }.sumOf { stampOf(it) }
    }.getOrDefault(0L)
  }

  private fun stampOf(path: Path): Long =
    runCatching { if (Files.exists(path)) Files.getLastModifiedTime(path).toMillis() else 0L }.getOrDefault(0L)

  companion object {
    /** `\n` is not a path character on any platform we ship to, unlike `:` and `;`. */
    private const val PATH_SEPARATOR = "\n"
    private const val KEY_REFERENCE_FOLDERS = "vibe.agent.referenceFolders"
    private const val KEY_SOURCE_FOLDERS = "vibe.agent.sourceFolders"

    fun getInstance(project: Project): ProjectContextService = project.service()
  }
}
