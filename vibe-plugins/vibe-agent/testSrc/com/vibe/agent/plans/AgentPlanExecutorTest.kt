// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.plans

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentPlanExecutorTest {
  private val plan = AgentPlan.Plan(listOf(AgentPlan.Step("шаг", AgentPlan.Status.PENDING)), 1, agent = "acp/claude")

  @Test
  fun `the executor survives the codec`() {
    assertEquals("acp/claude", AgentPlan.decode(AgentPlan.encode(plan)).agent)
  }

  @Test
  fun `another executor is named`() {
    assertEquals("acp/claude" to "acp/codex", AgentPlan.executorChange(plan, "acp/codex"))
  }

  @Test
  fun `same or unknown executor stays quiet`() {
    assertNull(AgentPlan.executorChange(plan, "acp/claude"))
    assertNull(AgentPlan.executorChange(plan, null))
    assertNull(AgentPlan.executorChange(plan.copy(agent = null), "acp/codex"))
  }
}
