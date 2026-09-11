// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillApprovalTest {
  private val files = mapOf("SKILL.md" to "a1", "scripts/extract.py" to "b2")

  @Test
  fun `approval is bound to the content, not to the name`() {
    // A skill lives in the repository: it arrives with a pull request and changes with a rebase.
    // «Я разрешил его вчера» therefore says nothing about what it does today.
    val yesterday = SkillApproval.digest(files)
    val today = SkillApproval.digest(files + ("scripts/extract.py" to "c3"))
    assertNotEquals(yesterday, today, "переписанный скрипт — другой скилл, хотя имена те же")
    assertEquals(SkillApproval.Verdict.CHANGED, SkillApproval.verdictFor(today, yesterday))
    assertEquals(SkillApproval.Verdict.UNCHANGED, SkillApproval.verdictFor(yesterday, yesterday))
    assertEquals(SkillApproval.Verdict.NEW, SkillApproval.verdictFor(today, null))
  }

  @Test
  fun `a new file beside the skill counts as a change`() {
    // A skill that gains a file gains a capability, even when nothing it already had changed.
    assertNotEquals(SkillApproval.digest(files), SkillApproval.digest(files + ("deploy.sh" to "d4")))
  }

  @Test
  fun `the filesystem's listing order is not a change`() {
    // Otherwise the question would be asked again for no reason, and a question asked for no
    // reason is a question people click through.
    val forward = linkedMapOf("a.sh" to "1", "b.md" to "2", "c/d.py" to "3")
    val backward = linkedMapOf("c/d.py" to "3", "b.md" to "2", "a.sh" to "1")
    assertEquals(SkillApproval.digest(forward), SkillApproval.digest(backward))
  }

  @Test
  fun `the digest is short, stable and hex`() {
    val digest = SkillApproval.digest(files)
    assertEquals(SkillApproval.DIGEST_LENGTH, digest.length)
    assertTrue(digest.all { it in "0123456789abcdef" }, digest)
    assertEquals(digest, SkillApproval.digest(files))
  }

  @Test
  fun `the changed files are named, not just counted`() {
    val changes = SkillApproval.changes(
      approved = files + ("old.md" to "o"),
      current = mapOf("SKILL.md" to "a1", "scripts/extract.py" to "zz", "scripts/new.sh" to "n"),
    )
    assertEquals(listOf("scripts/extract.py"), changes.modified)
    assertEquals(listOf("scripts/new.sh"), changes.added)
    assertEquals(listOf("old.md"), changes.removed)
    assertTrue(SkillApproval.changes(files, files).isEmpty)
  }

  @Test
  fun `the stored file map survives the settings store`() {
    assertEquals(files, SkillApproval.decodeFiles(SkillApproval.encodeFiles(files)))
    assertNull(SkillApproval.decodeFiles("a1b2c3d4e5f6a7b8"), "голый дайджест — не карта файлов")
  }
}
