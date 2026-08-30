// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.plans

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentPlanTest {
  private fun update(json: String) = Json.parseToJsonElement(json).jsonObject

  @Test
  fun `a plan update becomes steps with statuses`() {
    val plan = AgentPlan.parse(update("""
      {"entries":[
        {"content":"прочитать код","status":"completed"},
        {"content":"починить гейт","status":"in_progress"},
        {"content":"обновить доки","status":"pending"}]}
    """))
    assertEquals(3, plan.total)
    assertEquals(1, plan.done)
    assertEquals("починить гейт", plan.current?.content)
  }

  @Test
  fun `an unknown status counts as pending, never as done`() {
    // Ошибка в эту сторону теряет работу: законченный план никто не возобновляет.
    val plan = AgentPlan.parse(update("""{"entries":[{"content":"шаг","status":"чтототакое"}]}"""))
    assertEquals(AgentPlan.Status.PENDING, plan.steps.single().status)
    assertFalse(plan.isFinished)
  }

  @Test
  fun `an empty or malformed update yields an empty plan rather than throwing`() {
    assertTrue(AgentPlan.parse(update("{}")).isEmpty)
    assertTrue(AgentPlan.parse(update("""{"entries":[]}""")).isEmpty)
    assertTrue(AgentPlan.parse(update("""{"entries":[{"status":"pending"}]}""")).isEmpty)
  }

  @Test
  fun `the current step is the one in progress, or the first pending`() {
    val plan = AgentPlan.parse(update("""
      {"entries":[{"content":"раз","status":"completed"},{"content":"два","status":"pending"}]}
    """))
    assertEquals("два", plan.current?.content)
  }

  @Test
  fun `a finished plan has no current step`() {
    val plan = AgentPlan.parse(update("""{"entries":[{"content":"раз","status":"completed"}]}"""))
    assertTrue(plan.isFinished)
    assertNull(plan.current)
  }

  @Test
  fun `an empty plan is not a finished plan`() {
    // Иначе «плана нет» читалось бы как «всё сделано» и баннер продолжения не показался бы никогда.
    assertFalse(AgentPlan.Plan(emptyList()).isFinished)
  }

  @Test
  fun `a plan survives a round trip through json`() {
    val plan = AgentPlan.parse(update("""
      {"entries":[{"content":"раз","status":"completed","priority":"high"},{"content":"два","status":"in_progress"}]}
    """), nowMs = 12345)
    val restored = AgentPlan.decode(AgentPlan.encode(plan))
    assertEquals(plan.steps, restored.steps)
    assertEquals(12345, restored.updatedAtMs)
  }

  @Test
  fun `the plan is rendered as a checklist`() {
    val plan = AgentPlan.parse(update("""
      {"entries":[{"content":"раз","status":"completed"},{"content":"два","status":"pending"}]}
    """))
    val text = AgentPlan.render(plan) { if (it == AgentPlan.Status.COMPLETED) "[x]" else "[ ]" }
    assertEquals("[x] раз\n[ ] два", text)
  }

  @Test
  fun `a nested plan object is accepted too`() {
    // Некоторые адаптеры кладут entries внутрь plan — читать надо обе формы, а не падать на одной.
    val plan = AgentPlan.parse(update("""{"plan":{"entries":[{"content":"шаг","status":"pending"}]}}"""))
    assertEquals(1, plan.total)
  }
}
