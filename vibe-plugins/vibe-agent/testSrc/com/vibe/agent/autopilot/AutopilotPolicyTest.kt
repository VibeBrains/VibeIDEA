// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.autopilot

import com.vibe.agent.plans.AgentPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutopilotPolicyTest {
  private fun plan(vararg statuses: AgentPlan.Status) = AgentPlan.Plan(
    statuses.mapIndexed { i, status -> AgentPlan.Step("шаг $i", status) }
  )

  private fun state(
    enabled: Boolean = true,
    done: Int = 0,
    maxTurns: Int = 10,
    checkpointEvery: Int = 3,
    plan: AgentPlan.Plan? = plan(AgentPlan.Status.COMPLETED, AgentPlan.Status.PENDING),
    failed: Boolean = false,
    breaker: Boolean = false,
    stopped: Boolean = false,
  ) = AutopilotPolicy.State(enabled, done, maxTurns, checkpointEvery, plan, failed, breaker, stopped)

  @Test
  fun `выключенный автопилот не решает ничего`() {
    assertEquals(AutopilotPolicy.Decision.OFF, AutopilotPolicy.decide(state(enabled = false)))
  }

  @Test
  fun `без плана «продолжай» — догадка, а не команда`() {
    assertEquals(AutopilotPolicy.Decision.OFF, AutopilotPolicy.decide(state(plan = null)))
    assertEquals(AutopilotPolicy.Decision.OFF, AutopilotPolicy.decide(state(plan = AgentPlan.Plan(emptyList()))))
  }

  @Test
  fun `обычный ход по плану продолжается сам`() {
    assertEquals(AutopilotPolicy.Decision.CONTINUE, AutopilotPolicy.decide(state()))
  }

  @Test
  fun `любая необычность возвращает управление человеку`() {
    assertEquals(AutopilotPolicy.Decision.STOP_UNSAFE, AutopilotPolicy.decide(state(failed = true)))
    assertEquals(AutopilotPolicy.Decision.STOP_UNSAFE, AutopilotPolicy.decide(state(breaker = true)))
    assertEquals(AutopilotPolicy.Decision.STOP_UNSAFE, AutopilotPolicy.decide(state(stopped = true)))
  }

  @Test
  fun `неудача важнее выполненного плана`() {
    // Галочки могут стоять все, но последний шаг упал — это не «план выполнен».
    val done = plan(AgentPlan.Status.COMPLETED, AgentPlan.Status.COMPLETED)
    assertEquals(AutopilotPolicy.Decision.STOP_UNSAFE, AutopilotPolicy.decide(state(plan = done, failed = true)))
    assertEquals(AutopilotPolicy.Decision.STOP_PLAN_DONE, AutopilotPolicy.decide(state(plan = done)))
  }

  @Test
  fun `предел ходов подряд — это предел`() {
    assertEquals(AutopilotPolicy.Decision.STOP_LIMIT, AutopilotPolicy.decide(state(done = 10, maxTurns = 10)))
    // Ноль означает «без предела», а не «нельзя ни одного хода».
    assertEquals(AutopilotPolicy.Decision.CONTINUE, AutopilotPolicy.decide(state(done = 99, maxTurns = 0, checkpointEvery = 0)))
  }

  @Test
  fun `контрольная точка приходит по счёту ходов, ноль её отключает`() {
    assertEquals(AutopilotPolicy.Decision.CHECKPOINT, AutopilotPolicy.decide(state(done = 3, checkpointEvery = 3)))
    assertEquals(AutopilotPolicy.Decision.CONTINUE, AutopilotPolicy.decide(state(done = 3, checkpointEvery = 0)))
    assertEquals(AutopilotPolicy.Decision.CONTINUE, AutopilotPolicy.decide(state(done = 2, checkpointEvery = 3)))
  }

  @Test
  fun `вопрос на контрольной точке называет следующий шаг`() {
    val plan = AgentPlan.Plan(listOf(
      AgentPlan.Step("собрать", AgentPlan.Status.COMPLETED),
      AgentPlan.Step("прогнать тесты", AgentPlan.Status.IN_PROGRESS),
      AgentPlan.Step("обновить доки", AgentPlan.Status.PENDING),
    ))
    assertEquals("прогнать тесты", AutopilotPolicy.currentStep(plan))
    assertEquals(2, AutopilotPolicy.remaining(plan))
    assertNull(AutopilotPolicy.currentStep(null))
    assertEquals(0, AutopilotPolicy.remaining(null))
  }
}
