// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.context

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Instruction files an agent will read, and whether the project actually added them.
 *
 * `AGENTS.md` is not documentation: it is a command channel. An agent that opens one obeys it, and the file can arrive
 * without anyone deciding to add it — inside an unpacked archive, a sync folder or a stray download (a malicious
 * `AGENTS.md` in the Downloads folder hijacked Codex, 25.10.2026). A file ABOVE the project root is worse: it belongs
 * to whatever directory happens to contain the project.
 *
 * Pure except [scan]: the caller supplies what git answered, this decides what deserves a warning.
 */
object InstructionOrigin {
  /** Both names are read as instructions — by our agent and by the external agents it launches. */
  val NAMES: List<String> = listOf("AGENTS.md", "CLAUDE.md")

  /** Deep enough for a package's own file, shallow enough to stay cheap on a monorepo. */
  const val MAX_DEPTH = 8

  enum class Scope {
    /** Inside the project root. */
    ROOT,

    /** In a directory above the project root: someone else's project, or a folder that merely contains this one. */
    ABOVE,
  }

  /**
   * One instruction file worth naming.
   *
   * @property tracked null when git could not answer — no repository, no git, or a file outside it; a file is never
   * called untracked on a guess, because a false accusation teaches people to ignore the warning
   */
  data class Found(val path: String, val scope: Scope, val tracked: Boolean?)

  /**
   * Directories above [root] an agent searching upwards would find, nearest first.
   *
   * The user's home directory and everything above it are left out: a file there is not this project's instructions,
   * and every project on the machine would report the same one.
   */
  fun ancestors(root: Path, home: Path?): List<Path> {
    val stop = home?.toAbsolutePath()?.normalize()
    val found = ArrayList<Path>()
    var dir = root.toAbsolutePath().normalize().parent
    while (dir != null && dir != stop && dir.parent != null) {
      found.add(dir)
      dir = dir.parent
    }
    return found
  }

  /** Paths git reports as tracked, from `git ls-files -z`; anything that is not an instruction file is dropped. */
  fun parseTracked(output: String): Set<String> =
    output.split('\u0000').asSequence()
      .map { it.trim().replace('\\', '/') }
      .filter { it.isNotEmpty() && it.substringAfterLast('/') in NAMES }
      .toSet()

  /** Everything found, each with what is known about it; [warnings] is the subset that needs saying. */
  fun findings(inside: List<String>, tracked: Set<String>?, above: List<String>): List<Found> =
    inside.map { Found(it, Scope.ROOT, tracked?.contains(it.replace('\\', '/'))) } +
    above.map { Found(it, Scope.ABOVE, null) }

  /** An untracked file inside the project, or any instruction file above its root. */
  fun warnings(found: List<Found>): List<Found> = found.filter { it.scope == Scope.ABOVE || it.tracked == false }

  /** Relative paths of the instruction files under [root], walking past dependency trees but not into them. */
  fun scan(root: Path): List<String> {
    val found = ArrayList<String>()
    runCatching {
      Files.walkFileTree(root, emptySet(), MAX_DEPTH, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
          if (dir != root && ProjectFiles.skips(root.relativize(dir).toString())) return FileVisitResult.SKIP_SUBTREE
          return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
          if (attrs.isRegularFile && file.fileName.toString() in NAMES) {
            found.add(root.relativize(file).toString().replace('\\', '/'))
          }
          return FileVisitResult.CONTINUE
        }
      })
    }
    return found.sorted()
  }
}
