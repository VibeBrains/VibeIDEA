// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The agent may read a skill's eval cases but never rewrite them: it is the one being judged. */
class AccessPolicyEvalsTest {
  @Test
  fun `eval cases of any skill are protected`() {
    assertTrue(AccessPolicy.isProtectedEvals(".vibe/skills/example/evals/evals.json"))
    assertTrue(AccessPolicy.isProtectedEvals(".vibe/skills/review-pr/evals/fixtures/a.ts"))
  }

  @Test
  fun `case folding does not open a way around the rule`() {
    assertTrue(AccessPolicy.isProtectedEvals(".VIBE/Skills/example/EVALS/evals.json"))
  }

  @Test
  fun `the rest of a skill stays writable`() {
    assertFalse(AccessPolicy.isProtectedEvals(".vibe/skills/example/SKILL.md"))
    assertFalse(AccessPolicy.isProtectedEvals(".vibe/skills/example/scripts/run.sh"))
    assertFalse(AccessPolicy.isProtectedEvals("src/evals/evals.json"))
    assertFalse(AccessPolicy.isProtectedEvals(".vibe/skills/evals"))
  }
}
