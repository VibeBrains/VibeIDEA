// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.edits

/**
 * Which of the editor's own quick fixes may be applied to an agent's edit without asking.
 *
 * The point is economic: a missing import is an error the IDE has known how to fix since 2003,
 * and paying a model to notice it, explain it and rewrite the file is absurd. But the same
 * mechanism can silently rewrite logic, so the gate is narrow and stated in one place:
 *
 * - only fixes whose name says they add an import — the one class of fix that adds a line and
 *   changes no behaviour;
 * - only when the fix is UNAMBIGUOUS: two candidate imports for one name is a decision about
 *   which library the project uses, and that is the author's decision, not ours;
 * - only a few per file: an edit that needs fifteen imports fixed is an edit that went wrong,
 *   and quietly patching it hides that.
 */
object AutoFixPolicy {
  const val MAX_FIXES_PER_FILE = 5

  /** Substrings that identify an import fix across the languages we ship (ru/en interface aside). */
  private val IMPORT_MARKERS = listOf("import", "импорт")

  fun isImportFix(familyName: String): Boolean {
    val name = familyName.lowercase()
    return IMPORT_MARKERS.any { name.contains(it) }
  }

  /**
   * [candidatesPerError] — how many fixes the editor offers for one error. Anything but exactly
   * one means a choice, and a choice made silently is the kind of "help" people disable.
   */
  fun mayApply(familyName: String, candidatesPerError: Int, alreadyApplied: Int): Boolean =
    isImportFix(familyName) && candidatesPerError == 1 && alreadyApplied < MAX_FIXES_PER_FILE
}
