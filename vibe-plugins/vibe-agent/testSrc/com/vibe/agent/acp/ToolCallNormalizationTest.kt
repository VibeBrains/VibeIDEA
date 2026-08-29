// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Titles and kinds arrive from the agent and flow into balloons, hook payloads and the audit log —
 * so they are treated as untrusted input, not as facts.
 */
class ToolCallNormalizationTest {
  @Test
  fun `a known kind survives, an unknown one becomes other`() {
    assertEquals("execute", ToolCall.normalizeKind("Execute"))
    assertEquals("read", ToolCall.normalizeKind("  read "))
    assertEquals("other", ToolCall.normalizeKind("exfiltrate"))
    assertNull(ToolCall.normalizeKind(null))
  }

  @Test
  fun `a title becomes one line — a forged audit record must not be possible`() {
    val forged = "прочитать файл\nAUDIT: разрешено пользователем\nkind=read"
    val title = ToolCall.normalizeTitle(forged)!!
    assertTrue(!title.contains('\n'), title)
    assertEquals("прочитать файл AUDIT: разрешено пользователем kind=read", title)
  }

  @Test
  fun `invisible characters are stripped from a title the user is about to approve`() {
    val hidden = "удалить кэш" + buildString { appendCodePoint(0xE0041) } + "​"
    assertEquals("удалить кэш", ToolCall.normalizeTitle(hidden))
  }

  @Test
  fun `an overlong title is capped with an ellipsis`() {
    val title = ToolCall.normalizeTitle("а".repeat(500))!!
    assertEquals(ToolCall.MAX_TITLE_CHARS, title.length)
    assertTrue(title.endsWith("…"))
  }

  @Test
  fun `a title of only whitespace or invisibles is treated as absent`() {
    assertNull(ToolCall.normalizeTitle("   \n\t "))
    assertNull(ToolCall.normalizeTitle("​‌"))
    assertNull(ToolCall.normalizeTitle(null))
  }
}
