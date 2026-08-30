// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

/**
 * Who may touch what: the answer given BEFORE the agent acts, not explained after.
 *
 * Three kinds of place, and the difference between them is the whole point:
 * - the project — read and write, the ordinary case;
 * - reference folders (outside the project) and source folders (inside it) — READ ONLY.
 *   "Read my notes but do not edit them" is a sentence people say all the time, and until it
 *   is a rule the agent enforces, it is a hope;
 * - everything else outside the project — invisible.
 *
 * `.vibe/ignore` cuts across all three: an ignored file is not read even inside the project.
 *
 * Pure on purpose: the decision must be testable without a filesystem, because "did it really
 * refuse to write there?" is exactly the question one does not want to answer by experiment.
 */
object AccessPolicy {
  enum class Access { READ_WRITE, READ_ONLY, DENIED }

  data class Roots(
    /** Absolute path of the project root, slash-separated. */
    val projectBase: String?,
    /** Absolute paths outside the project the agent may READ. */
    val referenceFolders: List<String> = emptyList(),
    /** Paths relative to the project root the agent may read but never write. */
    val sourceFolders: List<String> = emptyList(),
    val ignore: VibeIgnore = VibeIgnore.EMPTY,
  )

  fun of(path: String, roots: Roots): Access {
    val normalized = normalize(path)
    val base = roots.projectBase?.let { normalize(it) }

    if (base != null && isInside(normalized, base)) {
      val relative = normalized.removePrefix(base).trim('/')
      if (roots.ignore.isIgnored(relative)) return Access.DENIED
      if (roots.sourceFolders.any { isInside(normalized, joinRelative(base, it)) }) return Access.READ_ONLY
      return Access.READ_WRITE
    }

    if (roots.referenceFolders.any { isInside(normalized, normalize(it)) }) return Access.READ_ONLY
    // Outside the project with no permission granted: not our business at all. A file the agent
    // cannot see is safer than a file it can read and quietly send to a model.
    return if (base == null) Access.READ_WRITE else Access.DENIED
  }

  fun mayRead(path: String, roots: Roots): Boolean = of(path, roots) != Access.DENIED

  fun mayWrite(path: String, roots: Roots): Boolean = of(path, roots) == Access.READ_WRITE

  /** `/a/b` contains `/a/b` and `/a/b/c`, but not `/a/bc` — the trap a bare startsWith falls into. */
  fun isInside(path: String, root: String): Boolean {
    if (root.isEmpty()) return false
    val cleanRoot = root.trimEnd('/')
    return path == cleanRoot || path.startsWith("$cleanRoot/")
  }

  fun normalize(path: String): String = path.replace('\\', '/').trimEnd('/').ifEmpty { "/" }

  private fun joinRelative(base: String, relative: String): String =
    normalize(base.trimEnd('/') + "/" + relative.trim('/'))
}
