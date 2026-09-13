// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.budget

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentLimitsTest {
  private fun parse(json: String) = AgentLimits.parse(Json.parseToJsonElement(json))

  @Test
  fun `the agreed field names are read`() {
    assertEquals(AgentLimits(5.0, 1.0, "USD", 200_000), parse("""{"costPerDay":5,"costPerRun":1.0,"currency":"USD","tokensPerDay":200000}"""))
  }

  @Test
  fun `empty, zero or missing limits are no limits`() {
    assertNull(parse("""{}"""))
    assertNull(parse("""{"costPerDay":0,"tokensPerDay":-1}"""))
    assertNull(AgentLimits.parse(null))
  }

  @Test
  fun `reaching a ceiling stops the next turn`() {
    val limits = AgentLimits(costPerDay = 5.0, currency = "USD")
    assertEquals(AgentLimits.Reason.COST_PER_DAY, limits.exceeded(0, 5.0, "USD", null, null)?.reason)
    assertNull(limits.exceeded(0, 4.99, "USD", null, null))
  }

  @Test
  fun `a run ceiling is checked against the run's bill`() {
    val limits = AgentLimits(costPerRun = 1.0)
    assertEquals(AgentLimits.Reason.COST_PER_RUN, limits.exceeded(0, 0.2, "USD", 1.2, "USD")?.reason)
  }

  @Test
  fun `tokens work where there is no price`() {
    val limits = AgentLimits(tokensPerDay = 1000)
    assertEquals(AgentLimits.Reason.TOKENS_PER_DAY, limits.exceeded(1000, null, null, null, null)?.reason)
  }

  @Test
  fun `another currency is not converted and not checked`() {
    val limits = AgentLimits(costPerDay = 5.0, currency = "USD")
    assertNull(limits.exceeded(0, 900.0, "RUB", null, null))
  }
}
