// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

import com.vibe.agent.i18n.VibeI18n.t

/**
 * Checks a skill package against the open Agent Skills contract.
 *
 * Exists because a broken skill fails silently in the worst possible way: `/skill:name` inserts a
 * token, the body never reaches the model, and the answer is merely… a bit worse than expected.
 * Nobody investigates a slightly worse answer. So the rules are checked up front and named out loud.
 */
object SkillValidator {
  enum class Level { ERROR, WARNING }

  data class Finding(val level: Level, val message: String)

  /** Caps: a skill has to fit in a prompt, and a description is a hint, not a chapter. */
  const val MAX_DESCRIPTION_CHARS = 1024
  const val MAX_BODY_CHARS = 100_000
  private val NAME_SHAPE = Regex("[a-z0-9]+(-[a-z0-9]+)*")
  const val MAX_NAME_CHARS = 64

  /**
   * @param attachments file names lying next to SKILL.md (for the path-escape and scripts checks).
   * @param escapingAttachments canonical paths that resolved OUTSIDE the skills tree — the caller
   *        does the resolving (it needs the filesystem), the verdict is here.
   */
  fun validate(
    pkg: SkillPackage,
    attachments: List<String> = emptyList(),
    escapingAttachments: List<String> = emptyList(),
  ): List<Finding> = buildList {
    if (!pkg.hasFrontmatter) {
      add(Finding(Level.ERROR, t("skill.error.noFrontmatter")))
    }
    when {
      pkg.name == null -> add(Finding(Level.ERROR, t("skill.error.noName")))
      pkg.name != pkg.id -> add(Finding(Level.ERROR, t("skill.error.nameMismatch", "name" to pkg.name, "id" to pkg.id)))
      pkg.name.length > MAX_NAME_CHARS -> add(Finding(Level.ERROR, t("skill.error.nameLong", "max" to MAX_NAME_CHARS)))
      !NAME_SHAPE.matches(pkg.name) -> add(Finding(Level.ERROR, t("skill.error.nameShape", "name" to pkg.name)))
    }
    when {
      pkg.description == null -> add(Finding(Level.ERROR, t("skill.error.noDescription")))
      pkg.description.length > MAX_DESCRIPTION_CHARS ->
        add(Finding(Level.WARNING, t("skill.warn.descriptionLong", "max" to MAX_DESCRIPTION_CHARS, "actual" to pkg.description.length)))
    }
    // Unknown top-level keys are an ERROR, not a nicety: the reference validator rejects the whole
    // package, so a skill that works here would be refused everywhere else.
    val unknown = pkg.topLevelKeys.filterNot { it in SkillPackage.ALLOWED_TOP_LEVEL }
    if (unknown.isNotEmpty()) {
      add(Finding(Level.ERROR, t("skill.error.unknownKeys", "keys" to unknown.joinToString(", "))))
    }
    if (pkg.body.isBlank()) add(Finding(Level.ERROR, t("skill.error.emptyBody")))
    else if (pkg.body.length > MAX_BODY_CHARS) {
      add(Finding(Level.WARNING, t("skill.warn.bodyLong", "max" to MAX_BODY_CHARS, "actual" to pkg.body.length)))
    }
    for (path in escapingAttachments) {
      add(Finding(Level.ERROR, t("skill.error.escapingAttachment", "path" to path, "tree" to SKILLS_TREE)))
    }
    if (attachments.any { it == "scripts" || it.startsWith("scripts/") }) {
      add(Finding(Level.WARNING, t("skill.warn.scripts")))
    }
  }

  private const val SKILLS_TREE = ".vibe/skills"

  fun hasErrors(findings: List<Finding>): Boolean = findings.any { it.level == Level.ERROR }
}
