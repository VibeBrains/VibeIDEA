// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.defaults

/**
 * Decisions of the real three-way merge of a conflicting seed file. Pure — no IO, no platform.
 *
 * The platform merge dialog gives three ways out per file: «Accept Yours», «Accept Theirs» and a
 * hand merge. The dialog reports only «resolved», not which one, so the choice is recovered from
 * the result: a file that came out equal to the release was taken from the release, anything else
 * is the person's own content (kept as is, or merged by hand).
 */
object SeedMerge {
  /**
   * The base of the merge — the release revision the person's copy was seeded from.
   * [known] is false for projects seeded before bases were recorded: the base is then empty,
   * and the merge window says so instead of showing an invented middle pane.
   */
  data class Base(val content: String, val known: Boolean)

  fun base(stored: String?): Base = if (stored != null) Base(stored, true) else Base("", false)

  enum class Choice {
    /** The file ended up equal to the release. */
    THEIRS,

    /** The person's own content, kept or merged by hand. */
    MINE,
  }

  /** Line-ending differences are not a choice: a CRLF copy of the release is still the release. */
  fun choice(resolved: String, release: String): Choice =
    if (resolved.replace("\r\n", "\n") == release.replace("\r\n", "\n")) Choice.THEIRS else Choice.MINE

  /**
   * Journal entry after a resolution.
   *
   * THEIRS — recorded as seeded at the release revision, so the next bump is measured from here.
   * MINE — the existing «keep mine» mechanism: the conflict stays quiet until the set moves past
   * [revisionVersion].
   */
  fun journalAfter(
    choice: Choice,
    current: VibeDefaults.JournalEntry?,
    releaseSha: String,
    revisionVersion: Int,
  ): VibeDefaults.JournalEntry = when (choice) {
    Choice.THEIRS -> VibeDefaults.JournalEntry(releaseSha, revisionVersion)
    Choice.MINE -> VibeDefaults.JournalEntry(current?.sha ?: "", current?.version, reconciled = revisionVersion)
  }

  /** Files still waiting for a decision after some of them were resolved. */
  fun pending(all: List<String>, resolved: Set<String>): List<String> = all.filter { it !in resolved }
}
