// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A session spans turns; reading one session must be a filter on a field, not a join over time. */
class AuditSessionTest {
  @Test
  fun `session id travels with the record`() {
    val json = AuditEvent(1L, AuditEvent.Action.PROMPT, true, AuditActor.HUMAN, turnId = "t1-1", sessionId = "thread-7").toJson()
    assertEquals("thread-7", json["sessionId"]?.toString()?.trim('"'))
  }

  @Test
  fun `absent session id leaves no empty field`() {
    assertNull(AuditEvent(1L, AuditEvent.Action.PROMPT, true, AuditActor.HUMAN).toJson()["sessionId"])
  }
}
