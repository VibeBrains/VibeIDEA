// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui.composer

import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.skills.SkillPackage


/**
 * Skills on disk: `<project>/.vibe/skills/<id>/SKILL.md`. The id is the directory name;
 * the description comes from the SKILL.md frontmatter (`description:`) or, without one,
 * from the first non-heading text line. Read on demand — the list is small and the
 * popup opens on an explicit user gesture.
 */
object SkillsRegistry {
  const val SKILLS_DIR = ".vibe/skills"
  const val SKILL_FILE = "SKILL.md"
  const val DESCRIPTION_LIMIT = 120

  class Skill(val id: String, val description: String, val broken: Boolean = false)

  /**
   * The list behind `/skill:`. A package with errors stays VISIBLE and says so: hiding it would
   * turn a typo in the frontmatter into a skill that vanished for no stated reason.
   */
  fun list(projectBase: String?): List<Skill> = com.vibe.agent.skills.SkillsStore.list(projectBase).map { entry ->
    val description = entry.pkg.description
      ?: entry.pkg.body.lineSequence().firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }.orEmpty()
    Skill(
      id = entry.pkg.id,
      description = if (entry.isBroken) "⚠ " + t("skills.broken") else clean(description),
      broken = entry.isBroken,
    )
  }

  /** Pure and testable: frontmatter `description:` wins, else the first plain text line. */
  fun parseDescription(text: String): String {
    // The skill package parser already knows quoted and block YAML scalars; a second reader here would drift from it.
    val pkg = SkillPackage.parse("", text)
    val description = pkg.description
      ?: pkg.body.lineSequence().firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }.orEmpty()
    return clean(description)
  }

  private fun clean(raw: String): String {
    val text = raw.trim()
    return if (text.length <= DESCRIPTION_LIMIT) text else text.take(DESCRIPTION_LIMIT) + "…"
  }
}
