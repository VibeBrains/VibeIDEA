// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

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
      add(Finding(Level.ERROR, "нет frontmatter: файл должен начинаться со строки --- и содержать name и description"))
    }
    when {
      pkg.name == null -> add(Finding(Level.ERROR, "нет обязательного поля name"))
      pkg.name != pkg.id -> add(Finding(Level.ERROR, "name «${pkg.name}» не совпадает с именем папки «${pkg.id}» — вызов /skill:${pkg.id} не найдёт его"))
      pkg.name.length > MAX_NAME_CHARS -> add(Finding(Level.ERROR, "name длиннее $MAX_NAME_CHARS символов"))
      !NAME_SHAPE.matches(pkg.name) -> add(Finding(Level.ERROR, "name «${pkg.name}»: допустимы строчные латинские буквы, цифры и дефис"))
    }
    when {
      pkg.description == null -> add(Finding(Level.ERROR, "нет обязательного поля description — по нему модель понимает, когда навык уместен"))
      pkg.description.length > MAX_DESCRIPTION_CHARS ->
        add(Finding(Level.WARNING, "description длиннее $MAX_DESCRIPTION_CHARS символов (${pkg.description.length}) — это подсказка, а не глава"))
    }
    // Unknown top-level keys are an ERROR, not a nicety: the reference validator rejects the whole
    // package, so a skill that works here would be refused everywhere else.
    val unknown = pkg.topLevelKeys.filterNot { it in SkillPackage.ALLOWED_TOP_LEVEL }
    if (unknown.isNotEmpty()) {
      add(Finding(Level.ERROR, "лишние ключи верхнего уровня: ${unknown.joinToString(", ")} — своё кладите под metadata:"))
    }
    if (pkg.body.isBlank()) add(Finding(Level.ERROR, "пустое тело: навык без инструкций ничего не даёт модели"))
    else if (pkg.body.length > MAX_BODY_CHARS) {
      add(Finding(Level.WARNING, "тело больше $MAX_BODY_CHARS символов (${pkg.body.length}) — в контекст оно не поместится целиком"))
    }
    for (path in escapingAttachments) {
      add(Finding(Level.ERROR, "вложение «$path» уводит за пределы $SKILLS_TREE — так навык может подтянуть чужой файл"))
    }
    if (attachments.any { it == "scripts" || it.startsWith("scripts/") }) {
      add(Finding(Level.WARNING, "рядом лежит scripts/ — исполняемое из навыка ничем не ограничено, проверьте содержимое"))
    }
  }

  private const val SKILLS_TREE = ".vibe/skills"

  fun hasErrors(findings: List<Finding>): Boolean = findings.any { it.level == Level.ERROR }
}
