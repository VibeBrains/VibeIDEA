// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The journals as they are actually written — in `.vibe/local/`.
 *
 * The rule knew only `.vibe/audit.jsonl`, while AuditLog and CheckpointService write through
 * VibeLocal into `.vibe/local/`: the live journal was open to the agent's fs/write. This test is the
 * reason that cannot silently come back.
 */
class AccessPolicyLiveJournalTest {
  @Test
  fun `live journals in local are protected`() {
    assertTrue(AccessPolicy.isProtectedJournal(".vibe/local/audit.jsonl"))
    assertTrue(AccessPolicy.isProtectedJournal(".vibe/local/checkpoints.jsonl"))
    assertTrue(AccessPolicy.isProtectedJournal(".vibe/local/audit.3.jsonl.gz"))
  }

  @Test
  fun `the path the writers use is the path the rule guards`() {
    val base = java.nio.file.Files.createTempDirectory("vibe-journal")
    try {
      for (name in listOf("audit.jsonl", "checkpoints.jsonl")) {
        val written = com.vibe.agent.defaults.VibeLocal.file(base.toString(), name)
        val relative = base.relativize(written).toString().replace('\\', '/')
        assertTrue(AccessPolicy.isProtectedJournal(relative), "writer path not guarded: $relative")
      }
    }
    finally {
      base.toFile().deleteRecursively()
    }
  }

  @Test
  fun `legacy location stays protected and neighbours stay open`() {
    assertTrue(AccessPolicy.isProtectedJournal(".vibe/audit.jsonl"))
    assertFalse(AccessPolicy.isProtectedJournal(".vibe/local/plans.json"))
    assertFalse(AccessPolicy.isProtectedJournal(".vibe/local/sub/audit.jsonl"))
    assertFalse(AccessPolicy.isProtectedJournal("src/audit.jsonl"))
  }
}
