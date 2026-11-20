// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.autopilot

import com.vibe.agent.plans.AgentPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    spentTokens: Long = 0,
    maxTokens: Long = 0,
    question: Boolean = false,
  ) = AutopilotPolicy.State(enabled, done, maxTurns, checkpointEvery, plan, spentTokens, maxTokens, failed, breaker, stopped, question)

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
  fun `ход, кончившийся вопросом, останавливает автопилот в любом режиме`() {
    // The model wrote a plan and asked «shall I start?» — the autopilot does not answer for the person
    assertEquals(AutopilotPolicy.Decision.STOP_QUESTION, AutopilotPolicy.decide(state(question = true)))
    // A question outranks a finished plan: the last word stays with the person
    val done = plan(AgentPlan.Status.COMPLETED, AgentPlan.Status.COMPLETED)
    assertEquals(AutopilotPolicy.Decision.STOP_QUESTION, AutopilotPolicy.decide(state(plan = done, question = true)))
  }

  @Test
  fun `вопросом считается последняя непустая строка ответа`() {
    assertTrue(AutopilotPolicy.endsWithQuestion("Готово. Приступать к реализации?"))
    assertTrue(AutopilotPolicy.endsWithQuestion("Готово.\n\nПриступать к реализации?  \n"))
    assertTrue(AutopilotPolicy.endsWithQuestion("Приступать к реализации?»"))
    assertTrue(AutopilotPolicy.endsWithQuestion("Приступать к реализации?**"))
    assertFalse(AutopilotPolicy.endsWithQuestion("Приступать к реализации?\nПлан готов."))
    assertFalse(AutopilotPolicy.endsWithQuestion("Готово."))
    assertFalse(AutopilotPolicy.endsWithQuestion(null))
    assertFalse(AutopilotPolicy.endsWithQuestion("   "))
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

  @Test
  fun `потолок расхода на отрезок — не то же, что предел ходов`() {
    // Один ход на большой модели дороже десяти на маленькой, поэтому деньги считаются отдельно.
    assertEquals(AutopilotPolicy.Decision.STOP_BUDGET, AutopilotPolicy.decide(state(spentTokens = 200_000, maxTokens = 200_000)))
    assertEquals(AutopilotPolicy.Decision.CONTINUE, AutopilotPolicy.decide(state(spentTokens = 199_999, maxTokens = 200_000)))
    // Ноль — выключенный потолок, а не запрет тратить.
    assertEquals(AutopilotPolicy.Decision.CONTINUE, AutopilotPolicy.decide(state(spentTokens = 10_000_000, maxTokens = 0)))
  }

  @Test
  fun `предел ходов проверяется раньше денег — он дешевле и понятнее`() {
    assertEquals(AutopilotPolicy.Decision.STOP_LIMIT,
                 AutopilotPolicy.decide(state(done = 10, maxTurns = 10, spentTokens = 999_999, maxTokens = 1_000)))
  }
}
