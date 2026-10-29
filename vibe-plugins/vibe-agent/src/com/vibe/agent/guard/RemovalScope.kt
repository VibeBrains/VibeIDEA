// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.guard

import java.nio.file.Path

/**
 * Whether a removal takes back only what a snapshot can put back.
 *
 * Autopilot asks about every `rm` today, and a run that builds and cleans ten times asks ten times — the same ritual
 * of unread dialogs that the permission modes exist to avoid. VibeIDE draws the line by scope: a removal whose every
 * target lies inside the project or a temp folder is not a question, because the checkpoint holds the old text and a
 * temp folder is scratch. Everything else stays a question: home, root, the repository store, a wildcard, a path
 * climbing out with `..`, a variable this rule cannot resolve, a second program on the line.
 *
 * Pure — strings in, verdict out — so a test decides it rather than a click on a dialog. Wrong in the safe direction
 * only: anything not understood is treated as irreversible and asked.
 */
object RemovalScope {

  /** Flags that change how `rm` walks, not what it touches: the targets are the whole story of the scope. */
  private val SAFE_FLAGS = Regex("^-[rRfvd]+$|^--(recursive|force|dir|verbose)$")

  /**
   * True when the line is a plain `rm` and every target it names is inside [projectRoot] or one of [tempRoots].
   *
   * [cwd] is where relative targets are read from; it must itself be inside one of the roots, since `../../..`
   * from a folder outside them would reach anywhere. A missing project root means no scope to judge by, so the answer
   * is false and the question is still asked.
   */
  fun isReversible(
    line: String,
    projectRoot: String?,
    cwd: String? = null,
    tempRoots: List<String> = defaultTempRoots(),
  ): Boolean {
    val root = projectRoot ?: return false
    val project = normalize(root).takeIf { it.nameCount > 0 } ?: return false
    val base = workingBase(cwd, project)?.takeIf { it.nameCount > 0 } ?: return false
    val roots = buildList {
      add(project)
      tempRoots.mapNotNullTo(this) { normalize(it).takeIf { p -> p.nameCount > 0 } }
    }
    if (roots.none { isInside(base, it) }) return false
    // A substitution, a backtick or an unquoted variable can name a second command or a target this rule never sees
    if (line.contains('$') || line.contains('`')) return false
    val segments = ShellSafetyAnalyzer.splitSegments(line)
    if (segments.isEmpty()) return false
    for ((command, args) in segments) {
      // Only `rm` itself: `sudo rm`, `xargs rm`, `sh -c "rm …"` all hide what actually runs, and stay a question
      if (baseName(command) != "rm") return false
      val targets = args.filterNot { it.startsWith("-") }
      // `rm -rf` with no target reads names from the standard input, and those names are not in the line
      if (targets.isEmpty()) return false
      if (args.any { it.startsWith("-") && !SAFE_FLAGS.matches(it) }) return false
      for (raw in targets) {
        val target = raw.trim('"', '\'')
        if (!plainPath(target)) return false
        val resolved = resolve(base, target) ?: return false
        // Never the project root itself, never the repository store: the checkpoint does not carry `.git`
        if (resolved == project) return false
        if (isInside(resolved, project.resolve(".git"))) return false
        // A temp root is scratch only as a folder to work in, not as something to delete whole
        if (roots.any { it == resolved }) return false
        if (roots.none { isInside(resolved, it) }) return false
      }
    }
    return true
  }

  /** Folders a system gives for scratch work: deleting inside them loses nothing that is not rebuilt. */
  fun defaultTempRoots(): List<String> = listOfNotNull(
    System.getProperty("java.io.tmpdir"),
    System.getenv("TMPDIR"),
    "/tmp",
    "/var/tmp",
  )

  private fun normalize(path: String): Path = Path.of(path.replace('\\', '/')).toAbsolutePath().normalize()

  /**
   * The folder relative targets are read from.
   *
   * A relative working folder is read from the project, not from the IDE's process: `.` means the project here, and
   * the process folder would make an innocent `rm build` look like it leaves the scope.
   */
  private fun workingBase(cwd: String?, project: Path): Path? = runCatching {
    val path = cwd?.takeIf { it.isNotBlank() }?.let { Path.of(it.replace('\\', '/')) }
    if (path == null || !path.isAbsolute) project.resolve(path ?: Path.of("")) else path
  }.getOrNull()?.toAbsolutePath()?.normalize()

  private fun baseName(token: String): String = token.substringAfterLast('/').substringAfterLast('\\')

  /** A target read as written: no wildcard, no home shortcut, no climb, no store, nothing the shell would expand. */
  private fun plainPath(target: String): Boolean {
    if (target.isEmpty() || target == "." || target == ".." || target == "/") return false
    if (target.startsWith("~")) return false
    if (target.any { it == '*' || it == '?' || it == '[' }) return false
    val parts = target.replace('\\', '/').split('/')
    return parts.none { it == ".." || it == ".git" }
  }

  private fun resolve(base: Path, target: String): Path? = runCatching {
    val path = Path.of(target.replace('\\', '/'))
    (if (path.isAbsolute) path else base.resolve(path)).normalize()
  }.getOrNull()

  private fun isInside(child: Path, root: Path): Boolean = child != root && child.startsWith(root)
}
