// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuditActorTest {
  @Test
  fun `every record names who caused it`() {
    // The defect this field exists for: a file written by the person and the same file written by
    // the agent during an unattended stretch used to be the same line in the journal.
    val byHand = AuditEvent(1L, AuditEvent.Action.FS_WRITE, ok = true, actor = AuditActor.HUMAN).toJson()
    val byAgent = AuditEvent(1L, AuditEvent.Action.FS_WRITE, ok = true,
                             actor = AuditActor.agent("developer", "acp:claude")).toJson()
    assertEquals("human", byHand.getValue("actor").jsonObject.getValue("kind").jsonPrimitive.content)
    assertEquals("agent", byAgent.getValue("actor").jsonObject.getValue("kind").jsonPrimitive.content)
    assertEquals("developer", byAgent.getValue("actor").jsonObject.getValue("role").jsonPrimitive.content)
    assertEquals("acp:claude", byAgent.getValue("actor").jsonObject.getValue("agent").jsonPrimitive.content)
  }

  @Test
  fun `our own checks are neither the person nor the agent`() {
    // Calling a verify gate or a circuit breaker «agent» would put the IDE's decisions on the
    // agent's account — the opposite of what the field is for.
    assertEquals("ide", AuditActor.IDE.toJson().getValue("kind").jsonPrimitive.content)
    assertNull(AuditActor.IDE.toJson()["role"])
    assertNull(AuditActor.IDE.toJson()["agent"])
  }

  @Test
  fun `blank role and agent are omitted rather than written as empty strings`() {
    val json = AuditActor.agent(role = "  ", agent = "").toJson()
    assertEquals("agent", json.getValue("kind").jsonPrimitive.content)
    assertNull(json["role"])
    assertNull(json["agent"])
  }
}
