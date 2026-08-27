// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ToolCallAuditTest {
  @Test
  fun commandToolsNeverGetAPath() {
    // Even if a path-shaped field were present, command tools reveal nothing.
    assertNull(ToolCallAudit.safeTargetPath("run_command", mapOf("path" to "/etc/passwd", "command" to "cat /etc/passwd")))
    assertNull(ToolCallAudit.safeTargetPath("run_persistent_command", mapOf("command" to "ls")))
  }

  @Test
  fun fileToolsGetTargetPathOnly() {
    assertEquals("/src/a.kt", ToolCallAudit.safeTargetPath("edit_file", mapOf("path" to "/src/a.kt", "content" to "secret")))
    assertEquals("/src/b.kt", ToolCallAudit.safeTargetPath("read_file", mapOf("uri" to "/src/b.kt")))
  }

  @Test
  fun pathTruncatedToLimit() {
    val long = "/" + "x".repeat(400)
    val result = ToolCallAudit.safeTargetPath("edit_file", mapOf("path" to long))!!
    assertEquals(ToolCallAudit.MAX_TARGET_LEN, result.length)
  }

  @Test
  fun noPathFieldReturnsNull() {
    assertNull(ToolCallAudit.safeTargetPath("some_tool", mapOf("query" to "find me")))
    assertNull(ToolCallAudit.safeTargetPath("edit_file", mapOf("path" to "   ")))
  }
}
