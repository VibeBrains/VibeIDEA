// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.context

import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads the rule files of one folder from disk: `.vibe/rules` (the family's own, recursive) and `.cursor/rules`
 * (foreign, flat)
 *
 * Separate from [ProjectContextService] because the reading is plain file work that needs no project:
 * a temp folder is enough to test which of two same-named rules wins
 *
 * One name — one rule, decided here and not left to the order of a later overwrite: [ProjectRules.applicable]
 * keys by name, and whichever file happened to come second would silently replace the first
 * The family's own folder wins, because that is the text the project wrote for this tool
 */
object ProjectRuleFiles {
  /**
   * Rules declared by [dir] under [base]; [dir] is empty for the project root, a package folder otherwise
   * Neither folder is an error when missing: most projects have only one of them, many have neither
   */
  fun read(base: Path, dir: String = ""): List<ProjectRules.Rule> {
    val root = if (dir.isEmpty()) base else base.resolve(dir)
    val native = readFolder(base, root, dir, ProjectRules.NATIVE_RULES_DIR, ProjectRules.NATIVE_MAX_DEPTH, ProjectRules.NATIVE_MAX_FILES)
    val foreign = readFolder(base, root, dir, ProjectRules.RULES_DIR, FLAT_DEPTH, Int.MAX_VALUE)
    val nativeNames = native.map { it.name }.toSet()
    return foreign.filter { it.name !in nativeNames } + native
  }

  /**
   * A cache token for the root rules: changes when a rule file is added, removed or edited in either folder
   * Folders are walked to the same depth as [read], so an edit deep in `.vibe/rules` reaches the next turn too
   */
  fun stamp(base: Path): Long =
    stampOf(base.resolve(ProjectRules.NATIVE_RULES_DIR), ProjectRules.NATIVE_MAX_DEPTH) * NATIVE_STAMP_WEIGHT +
      stampOf(base.resolve(ProjectRules.RULES_DIR), FLAT_DEPTH)

  private fun readFolder(
    base: Path,
    root: Path,
    dir: String,
    folderName: String,
    maxDepth: Int,
    maxFiles: Int,
  ): List<ProjectRules.Rule> {
    val folder = root.resolve(folderName)
    if (!Files.isDirectory(folder)) return emptyList()
    val files = runCatching {
      Files.walk(folder, maxDepth).use { stream ->
        stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(ProjectRules.RULE_EXTENSION) }.toList()
      }
    }.getOrDefault(emptyList())
    // Shallower first, then by path: the cap and the same-name tie both fall on the deeper, later file
    val ordered = files.sortedWith(compareBy({ folder.relativize(it).nameCount }, { folder.relativize(it).toString() }))
    val rules = ordered.take(maxFiles).mapNotNull { path ->
      val text = runCatching { Files.readString(path) }.getOrNull() ?: return@mapNotNull null
      val name = path.fileName.toString().removeSuffix(ProjectRules.RULE_EXTENSION)
      ProjectRules.parse(name, text, dir, base.relativize(path).joinToString("/"))
    }
    return rules.distinctBy { it.name }
  }

  private fun stampOf(folder: Path, maxDepth: Int): Long {
    if (!Files.isDirectory(folder)) return 0L
    return runCatching {
      Files.walk(folder, maxDepth).use { stream ->
        stream.toList().sumOf { entry -> runCatching { Files.getLastModifiedTime(entry).toMillis() + 1 }.getOrDefault(0L) }
      }
    }.getOrDefault(0L)
  }

  /** `.cursor/rules` is read flat: files directly inside the folder, as before. */
  private const val FLAT_DEPTH = 1

  /** Keeps an edit in one folder from cancelling an edit in the other inside the sum. */
  private const val NATIVE_STAMP_WEIGHT = 31L
}
