// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

import java.io.File

/** IO around the pure [SkillPackage] / [SkillValidator]: reading, path resolution, validation runs. */
object SkillsStore {
  class Entry(
    val pkg: SkillPackage,
    val findings: List<SkillValidator.Finding>,
    val dir: File,
    /** Every file of the skill, hashed — what gets approved, see [SkillFiles]. */
    val files: SkillFiles,
  ) {
    val isBroken: Boolean get() = SkillValidator.hasErrors(findings)

    /**
     * What the person approves: every file of the skill directory — SKILL.md with the header the
     * dialog shows first, and every script and attachment beside it, at any depth.
     */
    fun digest(): String = SkillApproval.digest(files.hashes)
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
    // Links leaving the skills tree are found by the walk, where the filesystem is; the verdict
    // lives in the validator.
    val files = SkillFiles.scan(root.toPath(), dir.toPath())
    val attachments = files.topLevel.filter { it != SkillPackage.SKILL_FILE }
    val findings = SkillValidator.validate(pkg, attachments, files.escaping, files.incomplete, files.scripts)
    return Entry(pkg, findings, dir, files)
  }
}
