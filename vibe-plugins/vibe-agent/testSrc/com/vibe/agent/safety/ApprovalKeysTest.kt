// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.safety

import kotlin.test.Test
import kotlin.test.assertEquals

/** Granting and revoking must read the same keys, or a revoke leaves something approved. */
class ApprovalKeysTest {
  @Test
  fun `revoke covers both skill keys and the command key`() {
    assertEquals(
      listOf("vibe.skill.approved.deploy", "vibe.skill.approvedFiles.deploy", "vibe.commands.approved.build"),
      ApprovalKeys.all(listOf("deploy"), listOf("build")),
    )
  }
}
