// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

import java.io.File

/** IO around the pure [SkillPackage] / [SkillValidator]: reading, path resolution, validation runs. */
object SkillsStore {
  class Entry(
    val pkg: SkillPackage,
    val findings: List<SkillValidator.Finding>,
    val dir: File,
    /** Everything shipped beside SKILL.md — part of what gets approved, see [SkillApproval]. */
    val attachments: List<String> = emptyList(),
  ) {
    val isBroken: Boolean get() = SkillValidator.hasErrors(findings)

    /** What the person approves when they approve this skill: the body plus the files beside it. */
    fun digest(): String = SkillApproval.digest(pkg.body, attachments)
  }

  fun root(projectBase: String?): File? = projectBase?.let { File(it, SkillPackage.SKILLS_DIR) }

  /** All packages of the project, validated, sorted by id. Call off the EDT (reads files). */
  fun list(projectBase: String?): List<Entry> {
    val root = root(projectBase) ?: return emptyList()
    val dirs = root.listFiles { f: File -> f.isDirectory } ?: return emptyList()
    return dirs.mapNotNull { dir -> load(root, dir) }.sortedBy { it.pkg.id }
  }

  fun find(projectBase: String?, id: String): Entry? {
    val root = root(projectBase) ?: return null
    val dir = File(root, id)
    if (!dir.isDirectory) return null
    return load(root, dir)
  }

  private fun load(root: File, dir: File): Entry? {
    val file = File(dir, SkillPackage.SKILL_FILE)
    if (!file.isFile) return null
    val text = runCatching { file.readText() }.getOrNull() ?: return null
    val pkg = SkillPackage.parse(dir.name, text)
    val attachments = (dir.listFiles() ?: emptyArray())
      .filter { it.name != SkillPackage.SKILL_FILE }
      .map { if (it.isDirectory) it.name + "/" else it.name }
    // A link pointing outside the skills tree would let a skill pull in an arbitrary file; the
    // verdict lives in the validator, the resolving has to happen here, where the filesystem is.
    val escaping = (dir.listFiles() ?: emptyArray())
      .filter { runCatching { !it.canonicalPath.startsWith(root.canonicalPath + File.separator) }.getOrDefault(true) }
      .map { it.name }
    return Entry(pkg, SkillValidator.validate(pkg, attachments, escaping), dir, attachments)
  }
}
