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

  private const val BYTES_IN_MB = 1024 * 1024

  /**
   * @param attachments names at the top of the skill directory (for the scripts warning).
   * @param escapingAttachments paths that resolved OUTSIDE the skills tree — the caller does the
   *        resolving (it needs the filesystem), the verdict is here.
   * @param incomplete why the directory could not be read whole: an approval cannot be bound to
   *        content nobody hashed, so such a skill is refused.
   * @param scripts text of the files under `scripts/`, scanned with the SKILL.md code blocks.
   */
  fun validate(
    pkg: SkillPackage,
    attachments: List<String> = emptyList(),
    escapingAttachments: List<String> = emptyList(),
    incomplete: SkillFiles.Incomplete? = null,
    scripts: Map<String, String> = emptyMap(),
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
    incomplete?.let { add(Finding(Level.ERROR, incompleteMessage(it))) }
    if (attachments.any { it == "scripts" || it.startsWith("scripts/") }) {
      add(Finding(Level.WARNING, t("skill.warn.scripts")))
    }
    addAll(codeFindings(pkg, scripts))
    addAll(hiddenTextFindings(pkg))
  }

  private fun incompleteMessage(incomplete: SkillFiles.Incomplete): String = when (incomplete.reason) {
    SkillFiles.Incomplete.Reason.FILES -> t("skill.error.tooManyFiles", "max" to SkillFiles.MAX_FILES)
    SkillFiles.Incomplete.Reason.BYTES -> t("skill.error.tooBig", "max" to SkillFiles.MAX_BYTES / BYTES_IN_MB)
    SkillFiles.Incomplete.Reason.DEPTH -> t("skill.error.tooDeep", "max" to SkillFiles.MAX_DEPTH)
    SkillFiles.Incomplete.Reason.UNREADABLE -> t("skill.error.unreadable", "path" to incomplete.path)
  }

  /** What the skill would have the agent run: SKILL.md code blocks and every script. */
  private fun codeFindings(pkg: SkillPackage, scripts: Map<String, String>): List<Finding> {
    val found = SkillCodeScan.scanSkill(pkg.body) + scripts.flatMap { (path, text) -> SkillCodeScan.scanScript(path, text) }
    return found.map { finding ->
      Finding(Level.WARNING, when (finding.kind) {
        SkillCodeScan.Kind.FETCH_AND_RUN -> t("skill.warn.fetchAndRun", "file" to finding.file, "command" to finding.detail)
        SkillCodeScan.Kind.UNPINNED_RUNNER -> t("skill.warn.unpinnedRunner", "file" to finding.file, "command" to finding.detail)
        SkillCodeScan.Kind.UNPINNED_PEP723 -> t("skill.warn.unpinnedPep723", "file" to finding.file, "dependency" to finding.detail)
      })
    }
  }

  /**
   * Спрятанный текст в скилле — проверяется ЗДЕСЬ, до первого использования.
   *
   * Санитайзер контекста вырезает невидимое на входе в контекст, и этого достаточно, чтобы модель
   * его не услышала. Но скилл живёт в репозитории и приезжает пул-реквестом: к моменту, когда его
   * впервые позовут, он уже одобрен человеком, прочитавшим чистый на вид текст. Проверка на
   * валидации — единственная точка, где о подмене можно узнать ДО одобрения, а не после.
   *
   * Заголовок и тело считаются отдельно: в заголовке невидимому символу оправдания нет вовсе
   * (`name` и `description` — это одна строка каждый), а в теле встречается честный эмодзи.
   * Поэтому в заголовке — ошибка при любом количестве, в теле — по шкале серьёзности.
   */
  private fun hiddenTextFindings(pkg: SkillPackage): List<Finding> = buildList {
    val header = com.vibe.agent.security.ContextSanitizer.sanitize(pkg.frontmatter)
    header.findings.firstOrNull { it.kind == com.vibe.agent.security.ContextSanitizer.Kind.INVISIBLE }?.let {
      add(Finding(Level.ERROR, t("skill.error.hiddenHeader", "count" to it.count)))
    }
    val body = com.vibe.agent.security.ContextSanitizer.sanitize(pkg.body)
    body.findings.firstOrNull { it.kind == com.vibe.agent.security.ContextSanitizer.Kind.INVISIBLE }?.let {
      val level =
        if (it.severity >= com.vibe.agent.security.ContextSanitizer.Severity.HIGH) Level.ERROR else Level.WARNING
      add(Finding(level, t("skill.warn.hiddenBody", "count" to it.count, "run" to it.longestRun)))
    }
    if (header.findings.any { it.kind == com.vibe.agent.security.ContextSanitizer.Kind.BIDI } ||
        body.findings.any { it.kind == com.vibe.agent.security.ContextSanitizer.Kind.BIDI }) {
      add(Finding(Level.ERROR, t("skill.error.bidi")))
    }
  }

  private const val SKILLS_TREE = ".vibe/skills"

  fun hasErrors(findings: List<Finding>): Boolean = findings.any { it.level == Level.ERROR }
}
