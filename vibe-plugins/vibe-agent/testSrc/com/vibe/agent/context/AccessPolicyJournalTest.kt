// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessPolicyJournalTest {
  private val roots = AccessPolicy.Roots(projectBase = "/p")

  @Test
  fun `the agent cannot rewrite the record of what it did`() {
    // Until this rule the journals were ordinary project files: writable, with only a confirmation
    // dialog in the way. An investigation that has to trust the subject's own record is none.
    assertEquals(AccessPolicy.Access.READ_ONLY, AccessPolicy.of("/p/.vibe/audit.jsonl", roots))
    assertEquals(AccessPolicy.Access.READ_ONLY, AccessPolicy.of("/p/.vibe/checkpoints.jsonl", roots))
    assertFalse(AccessPolicy.mayWrite("/p/.vibe/audit.jsonl", roots))
  }

  @Test
  fun `it may still read its own trail`() {
    // READ_ONLY, not DENIED: «что ты уже делал» is a fair question, and the danger is the rewrite.
    assertTrue(AccessPolicy.mayRead("/p/.vibe/audit.jsonl", roots))
  }

  @Test
  fun `rotated archives are protected too`() {
    // Protecting only the live file would leave everything older than the last rotation
    // rewritable — precisely the part an investigation reads.
    assertEquals(AccessPolicy.Access.READ_ONLY, AccessPolicy.of("/p/.vibe/audit.1.jsonl.gz", roots))
    assertEquals(AccessPolicy.Access.READ_ONLY, AccessPolicy.of("/p/.vibe/audit.12.jsonl.gz", roots))
  }

  @Test
  fun `no setting can open the journal for writing`() {
    // Neither ignore rules nor source folders may flip this: a setting that could would be a
    // setting that switches accountability off.
    val configured = AccessPolicy.Roots(
      projectBase = "/p",
      sourceFolders = listOf("src"),
      ignore = VibeIgnore.parse(".vibe/audit.jsonl"),
    )
    assertEquals(AccessPolicy.Access.READ_ONLY, AccessPolicy.of("/p/.vibe/audit.jsonl", configured))
  }

  @Test
  fun `ordinary vibe files stay writable`() {
    // The rule is narrow on purpose: providers.json, plans and rules are the agent's to edit.
    assertEquals(AccessPolicy.Access.READ_WRITE, AccessPolicy.of("/p/.vibe/providers.json", roots))
    assertEquals(AccessPolicy.Access.READ_WRITE, AccessPolicy.of("/p/.vibe/rules.md", roots))
    assertEquals(AccessPolicy.Access.READ_WRITE, AccessPolicy.of("/p/src/audit.jsonl", roots))
  }
}
