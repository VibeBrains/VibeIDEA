// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuditReadSummaryTest {
  private val lines = listOf(
    """{"ts":1,"action":"tool_call:done","ok":true,"actor":{"kind":"agent","agent":"acp:claude"},"sessionId":"s1","files":["src/secret.env"],"meta":{"tool":"Read"}}""",
    """{"ts":2,"action":"tool_call:done","ok":true,"actor":{"kind":"agent","agent":"acp:claude"},"sessionId":"s1","files":["src/App.kt"],"meta":{"tool":"Edit"}}""",
    """{"ts":3,"action":"fs_write","ok":true,"actor":{"kind":"agent","agent":"acp:codex"},"sessionId":"s2","files":["README.md"]}""",
    """{"ts":4,"action":"tool_call:done","ok":true,"actor":{"kind":"agent","agent":"acp:claude"},"sessionId":"s1","meta":{"tool":"Bash"}}""",
    """{"ts":5,"action":"permission","ok":true,"actor":{"kind":"human"},"sessionId":"s1"}""",
    "не json",
  )

  @Test
  fun `reads, writes and commands are counted per agent`() {
    val claude = AuditReadSummary.of(lines).first { it.name == "acp:claude" }
    assertEquals(setOf("src/secret.env"), claude.read)
    assertEquals(setOf("src/App.kt"), claude.written)
    assertEquals(1, claude.commands)
  }

  @Test
  fun `a session filter narrows to one conversation`() {
    val names = AuditReadSummary.of(lines, sessionId = "s2").map { it.name }
    assertEquals(listOf("acp:codex"), names)
  }

  @Test
  fun `human records and broken lines are not agents`() {
    assertTrue(AuditReadSummary.of(lines).none { it.name == AuditReadSummary.UNKNOWN })
  }
}
