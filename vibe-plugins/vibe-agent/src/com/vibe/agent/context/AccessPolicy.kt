// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

import java.text.Normalizer
import java.util.Locale

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
 * And one place is READ_ONLY no matter what anyone configures: the journals the agent's own
 * actions are written into. They sat inside the project, which made them ordinary writable files —
 * the agent could rewrite the record of what it had done, and the only thing standing in the way
 * was a confirmation dialog. A dialog is a procedure; this is a wall. The July 2026 Hugging Face
 * intrusion is the reason the difference matters: an investigation that has to trust the subject's
 * own record is not an investigation.
 *
 * Pure on purpose: the decision must be testable without a filesystem, because "did it really
 * refuse to write there?" is exactly the question one does not want to answer by experiment.
 *
 * The path must arrive already resolved — see [AgentPaths]: `..` gone, symbolic links followed.
 * Text that still walks through `..` is a route rather than a place, and a route can leave any
 * folder it seems to be in; such a path is refused here instead of being guessed at.
 *
 * Case and Unicode form: every DENY-type comparison (the journals, `.vibe/ignore`, source folders)
 * folds both, because on APFS and NTFS `Secrets/` and `secrets/` are one folder and «й» has two byte
 * spellings. ALLOW-type comparisons (the project, reference folders) stay exact: both sides are
 * resolved through the file system first, and an exact miss can only err towards refusing.
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

  /**
   * Records of what the agent did, written by us and never by it.
   *
   * READ_ONLY rather than DENIED deliberately: the agent may be asked «что ты уже делал» and read
   * its own trail, and hiding it would cost a real capability to buy nothing — the danger is the
   * rewrite, not the reading.
   */
  val PROTECTED_JOURNALS: Set<String> = setOf(
    ".vibe/audit.jsonl",
    ".vibe/checkpoints.jsonl",
    ".vibe/local/audit.jsonl",
    ".vibe/local/checkpoints.jsonl",
  )

  /**
   * Is this project-relative path one of the journals?
   *
   * Rotated archives count: `audit.jsonl` is rotated into `audit.1.jsonl.gz`, and protecting only
   * the live file would leave everything older than the last rotation rewritable — which is
   * precisely the part an investigation reads.
   */
  fun isProtectedJournal(relative: String): Boolean {
    // Folded: on a case-insensitive disk `.VIBE/AUDIT.JSONL` is the journal itself.
    val key = foldCase(relative)
    if (key in PROTECTED_JOURNALS) return true
    // The journals are written to `.vibe/local/` (runtime state that does not travel with the repo),
    // and the rule used to know only the old `.vibe/` location — so the LIVE journal was writable by
    // the agent through plain fs/write while its legacy path was guarded. Both folders count: old
    // projects still carry files there until the migration moves them.
    val name = JOURNAL_FOLDERS.firstNotNullOfOrNull { folder -> key.removePrefix(folder).takeIf { it != key } }
               ?: return false
    if (name.contains('/')) return false
    return (name.startsWith("audit.") || name.startsWith("checkpoints.")) &&
           (name.endsWith(".jsonl") || name.endsWith(".jsonl.gz"))
  }

  /** Where the journals live: `.vibe/local/` now, `.vibe/` in projects not yet migrated. Longest first. */
  private val JOURNAL_FOLDERS = listOf(".vibe/local/", ".vibe/")

  /**
   * Is this project-relative path inside a skill's eval cases (`.vibe/skills/<id>/evals/…`)?
   *
   * Eval cases judge the agent, so the agent must not rewrite them: tuning your own grader is the
   * core of the 2026 OpenAI – Hugging Face incident. Skill approval is bound to the whole directory
   * digest, so an edit would drop the approval — but dropping it after the fact is not the same as
   * refusing the write. Reading stays open: the agent may need the cases to understand the skill.
   */
  fun isProtectedEvals(relative: String): Boolean {
    val parts = foldCase(relative).split('/')
    return parts.size >= 4 && parts[0] == ".vibe" && parts[1] == "skills" && parts[3] == "evals"
  }

  fun of(path: String, roots: Roots): Access {
    val normalized = normalize(path)
    val base = roots.projectBase?.let { normalize(it) }

    if (base != null) {
      if (hasDotSegments(normalized)) return Access.DENIED
      val relative = relativeTo(normalized, base)
      if (relative != null) {
        // Before every other rule, and not overridable by any of them: a setting that could open the
        // journal for writing would be a setting that switches accountability off.
        if (isProtectedJournal(relative)) return Access.READ_ONLY
        // Same rank as the journals, for the same reason: no setting may let the judged edit the judge.
        if (isProtectedEvals(relative)) return Access.READ_ONLY
        if (roots.ignore.isIgnored(relative)) return Access.DENIED
        if (roots.sourceFolders.any { isInside(foldCase(normalized), foldCase(joinRelative(base, it))) }) {
          return Access.READ_ONLY
        }
        return Access.READ_WRITE
      }
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

  /** [path] relative to [root] («» for the root itself), or null when it is not inside; exact comparison. */
  fun relativeTo(path: String, root: String): String? {
    val normalized = normalize(path)
    val cleanRoot = normalize(root)
    if (!isInside(normalized, cleanRoot)) return null
    return normalized.removePrefix(cleanRoot.trimEnd('/')).trim('/')
  }

  /**
   * The form deny-type comparisons are made in: Unicode NFC, lower case.
   *
   * For deny rules only. Folding an allow rule would, on a case-sensitive disk, let `/work/APP`
   * pass for `/work/app` — a different folder there.
   */
  fun foldCase(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)

  private fun hasDotSegments(path: String): Boolean = path.split('/').any { it == "." || it == ".." }

  private fun joinRelative(base: String, relative: String): String =
    normalize(base.trimEnd('/') + "/" + relative.trim('/'))
}
