// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.safety

/**
 * Where a person's «yes, run it» is remembered, per project.
 *
 * One place for the keys, because granting and revoking are the same fact read in two directions: a
 * revoke that forgot one prefix would leave a skill approved while telling the person it is not.
 */
object ApprovalKeys {
  /** `+ skillId` → the approved digest of the whole skill directory. */
  const val SKILL = "vibe.skill.approved."

  /** `+ skillId` → the file map the digest was taken over, to name what changed next time. */
  const val SKILL_FILES = "vibe.skill.approvedFiles."

  /** `+ commandId` → the hash of the exact command text that was approved. */
  const val COMMAND = "vibe.commands.approved."

  /** Every key a revoke must clear for these skills and commands. */
  fun all(skillIds: Collection<String>, commandIds: Collection<String>): List<String> =
    skillIds.flatMap { listOf(SKILL + it, SKILL_FILES + it) } + commandIds.map { COMMAND + it }
}
