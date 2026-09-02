// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SkillApprovalTest {
  private val body = "# Grill\nПрогони тесты и почини красное."

  @Test
  fun `approval is bound to the content, not to the name`() {
    // A skill lives in the repository: it arrives with a pull request and changes with a rebase.
    // «Я разрешил его вчера» therefore says nothing about what it does today.
    val yesterday = SkillApproval.digest(body)
    val today = SkillApproval.digest(body + "\nИ выложи в прод.")
    assertNotEquals(yesterday, today)
    assertEquals(SkillApproval.Verdict.CHANGED, SkillApproval.verdictFor(today, yesterday))
    assertEquals(SkillApproval.Verdict.UNCHANGED, SkillApproval.verdictFor(yesterday, yesterday))
    assertEquals(SkillApproval.Verdict.NEW, SkillApproval.verdictFor(today, null))
  }

  @Test
  fun `a new file beside the skill counts as a change`() {
    // The body may be untouched while the recipe now points at a script that was not there
    // yesterday — that is a new capability, not a new comment.
    val plain = SkillApproval.digest(body, listOf("README.md"))
    val armed = SkillApproval.digest(body, listOf("README.md", "deploy.sh"))
    assertNotEquals(plain, armed)
  }

  @Test
  fun `the filesystem's listing order is not a change`() {
    // Otherwise the question would be asked again for no reason, and a question asked for no
    // reason is a question people click through.
    assertEquals(
      SkillApproval.digest(body, listOf("a.sh", "b.md", "c/")),
      SkillApproval.digest(body, listOf("c/", "a.sh", "b.md")),
    )
  }

  @Test
  fun `the digest is short, stable and hex`() {
    val digest = SkillApproval.digest(body)
    assertEquals(SkillApproval.DIGEST_LENGTH, digest.length)
    assertTrue(digest.all { it in "0123456789abcdef" }, digest)
    assertEquals(digest, SkillApproval.digest(body))
  }
}
