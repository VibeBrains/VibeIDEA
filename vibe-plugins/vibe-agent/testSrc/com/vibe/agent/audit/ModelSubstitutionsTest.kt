// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Substitutions summed up from the audit log: counted per pair, most frequent first, other events and junk ignored */
class ModelSubstitutionsTest {
  private fun event(asked: String, answered: String, ts: Long) =
    AuditEvent(ts, AuditEvent.Action.MODEL_SUBSTITUTED, ok = false, actor = AuditActor.HUMAN,
               meta = mapOf("asked" to asked, "answered" to answered)).toJson().toString()

  @Test
  fun `pairs are counted, the most frequent first, with the latest time`() {
    val lines = listOf(
      event("claude-opus-5-5", "claude-opus-4-8", 10),
      "not json",
      AuditEvent(11, AuditEvent.Action.PROMPT, ok = true, actor = AuditActor.HUMAN).toJson().toString(),
      event("gpt-6-sol", "gpt-6-luna", 12),
      event("claude-opus-5-5", "claude-opus-4-8", 30),
    )
    val summary = ModelSubstitutions.of(lines)
    assertEquals(listOf(ModelSubstitutions.Entry("claude-opus-5-5", "claude-opus-4-8", 2, 30),
                        ModelSubstitutions.Entry("gpt-6-sol", "gpt-6-luna", 1, 12)), summary)
    assertTrue(ModelSubstitutions.of(emptyList()).isEmpty())
  }
}
