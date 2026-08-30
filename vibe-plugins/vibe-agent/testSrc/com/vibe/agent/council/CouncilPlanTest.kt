// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.council

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CouncilPlanTest {
  @Test
  fun `advisers are parsed from a comma or newline list`() {
    val plan = CouncilPlan.parse("zai/glm-4.6, minimax/abab6\nopenai/gpt-4o")
    assertEquals(3, plan.advisers.size)
    assertEquals("zai/glm-4.6", plan.advisers.first().toString())
  }

  @Test
  fun `the same model twice is not two opinions`() {
    // Одна и та же модель, спрошенная дважды, соглашается сама с собой — это не подтверждение.
    val plan = CouncilPlan.parse("zai/glm-4.6, zai/glm-4.6")
    assertEquals(1, plan.advisers.size)
    assertFalse(plan.isUsable)
  }

  @Test
  fun `a council of one is not a council`() {
    assertFalse(CouncilPlan.parse("zai/glm-4.6").isUsable)
    assertTrue(CouncilPlan.parse("zai/glm-4.6, minimax/abab6").isUsable)
  }

  @Test
  fun `malformed entries are reported, not silently dropped`() {
    // Совет, тихо оставшийся с одним советником, хуже отсутствия совета: его ответ ВЫГЛЯДИТ согласием.
    val plan = CouncilPlan.parse("zai/glm-4.6, простотекст, /нет-провайдера, minimax/")
    assertEquals(listOf("простотекст", "/нет-провайдера", "minimax/"), plan.problems)
  }

  @Test
  fun `an unknown provider is a problem, not an adviser`() {
    val plan = CouncilPlan.parse("zai/glm-4.6, ghost/model", unknownProvider = { it == "ghost" })
    assertEquals(1, plan.advisers.size)
    assertEquals(listOf("ghost/model"), plan.problems)
  }

  @Test
  fun `the council has an upper bound`() {
    val spec = (1..10).joinToString(",") { "p$it/m$it" }
    assertEquals(CouncilPlan.MAX_ADVISERS, CouncilPlan.parse(spec).advisers.size)
  }

  @Test
  fun `every adviser gets exactly the same question`() {
    val a = CouncilPlan.adviserPrompt("вопрос", "инструкция")
    val b = CouncilPlan.adviserPrompt("вопрос", "инструкция")
    assertEquals(a, b)
    assertTrue(a.contains("вопрос") && a.contains("инструкция"))
  }

  @Test
  fun `answers are numbered rather than attributed`() {
    // Судья, знающий, какой ответ от знаменитой модели, соглашается со знаменитой моделью.
    val text = CouncilPlan.synthesisPrompt("вопрос", listOf("ответ один", "ответ два"), "сведи")
    assertTrue(text.contains("--- 1 ---"))
    assertTrue(text.contains("--- 2 ---"))
    assertFalse(text.contains("gpt"))
  }
}
