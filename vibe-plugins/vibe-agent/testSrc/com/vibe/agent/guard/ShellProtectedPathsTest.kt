// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.guard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shell is the one way around AccessPolicy: a command never passes through fs/write.
 * A command that rewrites the journal or eval cases is treated as destructive and asks the human.
 */
class ShellProtectedPathsTest {
  @Test
  fun `writing into the live journal asks the human`() {
    val verdict = ShellSafetyAnalyzer.analyzeLine("echo '{}' >> .vibe/local/audit.jsonl")
    assertEquals(ShellSafetyAnalyzer.Safety.DESTRUCTIVE, verdict?.safety)
    assertTrue(ShellSafetyAnalyzer.PROTECTED_JOURNAL in verdict!!.reasons)
  }

  @Test
  fun `overwriting eval cases asks the human`() {
    val verdict = ShellSafetyAnalyzer.analyzeLine("cp tuned.json .vibe/skills/example/evals/evals.json")
    assertTrue(ShellSafetyAnalyzer.PROTECTED_EVALS in verdict!!.reasons)
  }

  @Test
  fun `deleting a checkpoint journal through a wrapper is caught`() {
    val verdict = ShellSafetyAnalyzer.analyzeLine("sudo mv ./.vibe/local/checkpoints.jsonl /tmp/x")
    assertTrue(ShellSafetyAnalyzer.PROTECTED_JOURNAL in verdict!!.reasons)
  }

  @Test
  fun `reading the journal stays quiet`() {
    assertNull(ShellSafetyAnalyzer.analyzeLine("tail -n 50 .vibe/local/audit.jsonl"))
    assertNull(ShellSafetyAnalyzer.analyzeLine("jq . .vibe/local/audit.jsonl"))
  }

  @Test
  fun `ordinary project files stay quiet`() {
    assertNull(ShellSafetyAnalyzer.analyzeLine("cp a.json src/evals/evals.json"))
    assertNull(ShellSafetyAnalyzer.analyzeLine("echo hi >> notes.txt"))
  }

  @Test
  fun `windows separators and absolute paths fold to the same rule`() {
    assertEquals(ShellSafetyAnalyzer.PROTECTED_JOURNAL, ShellSafetyAnalyzer.protectedPathReason("C:\\work\\proj\\.vibe\\local\\audit.jsonl"))
    assertEquals(ShellSafetyAnalyzer.PROTECTED_JOURNAL, ShellSafetyAnalyzer.protectedPathReason("/home/me/proj/.vibe/local/audit.jsonl"))
  }
}
