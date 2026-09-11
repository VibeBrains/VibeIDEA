// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

import com.vibe.agent.i18n.VibeI18n.t

/**
 * What the approval dialog says: everything the person is about to authorise, in reading order.
 *
 * Until 11.09.2026 it said an id and 400 characters of the body, while the agent went on to run
 * the whole directory — the person approved a paragraph. Now it shows what the header claims
 * (`description`, `allowed-tools`, `compatibility`), every file with the runnable ones marked, and,
 * for a changed skill, which files changed since the approval.
 */
object SkillApprovalText {
  /** How much of SKILL.md to quote: enough to recognise the recipe, not a substitute for opening it. */
  const val PREVIEW_CHARS = 400

  /** Paths listed before «…and N more»: the list is for spotting a stranger, not for reading. */
  const val LISTED_PATHS = 20

  fun render(id: String, entry: SkillsStore.Entry, verdict: SkillApproval.Verdict, approvedFiles: Map<String, String>?): String =
    render(id, entry.pkg, entry.files, verdict, approvedFiles)

  /**
   * @param approvedFiles the file map stored with the previous approval; null for a skill never
   *   approved, or approved under the old rule that stored no map.
   */
  fun render(
    id: String,
    pkg: SkillPackage,
    files: SkillFiles,
    verdict: SkillApproval.Verdict,
    approvedFiles: Map<String, String>?,
  ): String {
    val parts = ArrayList<String>()
    parts += when {
      // An approval from before the whole directory counted: the content may be the same, but
      // what the approval covers is not — say so instead of pretending the skill changed.
      verdict == SkillApproval.Verdict.CHANGED && approvedFiles == null -> t("skills.approve.rescoped", "id" to id)
      verdict == SkillApproval.Verdict.CHANGED -> t("skills.approve.changed", "id" to id)
      else -> t("skills.approve.new", "id" to id)
    }
    val header = listOfNotNull(
      pkg.description?.let { t("skills.approve.description", "text" to it) },
      pkg.field("allowed-tools")?.let { t("skills.approve.allowedTools", "tools" to it) },
      pkg.field("compatibility")?.let { t("skills.approve.compatibility", "text" to it) },
    )
    if (header.isNotEmpty()) parts += header.joinToString("\n")
    if (verdict == SkillApproval.Verdict.CHANGED && approvedFiles != null) {
      val changes = SkillApproval.changes(approvedFiles, files.hashes)
      if (!changes.isEmpty) {
        parts += listOfNotNull(
          t("skills.approve.changes"),
          changes.modified.takeIf { it.isNotEmpty() }?.let { t("skills.approve.modified", "paths" to paths(it)) },
          changes.added.takeIf { it.isNotEmpty() }?.let { t("skills.approve.added", "paths" to paths(it)) },
          changes.removed.takeIf { it.isNotEmpty() }?.let { t("skills.approve.removed", "paths" to paths(it)) },
        ).joinToString("\n")
      }
    }
    parts += buildList {
      add(t("skills.approve.files", "count" to files.entries.size))
      for (entry in files.entries.take(LISTED_PATHS)) {
        add(INDENT + entry.path + if (entry.executable) SEPARATOR + t("skills.approve.executable") else "")
      }
      if (files.entries.size > LISTED_PATHS) add(INDENT + t("skills.approve.more", "count" to files.entries.size - LISTED_PATHS))
    }.joinToString("\n")
    parts += t("skills.approve.preview") + "\n" + pkg.body.take(PREVIEW_CHARS)
    return parts.joinToString("\n\n")
  }

  private fun paths(list: List<String>): String {
    val shown = list.take(LISTED_PATHS).joinToString(", ")
    return if (list.size > LISTED_PATHS) shown + ", " + t("skills.approve.more", "count" to list.size - LISTED_PATHS) else shown
  }

  private const val INDENT = "  "
  private const val SEPARATOR = " — "
}
