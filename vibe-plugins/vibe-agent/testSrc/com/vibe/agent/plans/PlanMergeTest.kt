// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.plans

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanMergeTest {
  private fun plan(text: String, at: Long) =
    AgentPlan.Plan(listOf(AgentPlan.Step(text, AgentPlan.Status.PENDING)), updatedAtMs = at)

  @Test
  fun `чужой план из файла не затирается нашей записью`() {
    // Ровно тот случай, ради которого мерж и делается: второе окно сохраняет своё и уносит чужое.
    val onDisk = mapOf("чужой" to plan("их шаг", 100))
    val merged = PlanMerge.merge(onDisk, mine = emptyMap(), ownThread = "мой", ownPlan = plan("мой шаг", 200))
    assertEquals(setOf("чужой", "мой"), merged.keys)
    assertEquals("их шаг", merged.getValue("чужой").steps.single().content)
  }

  @Test
  fun `для своего потока авторитет — пишущий, а не файл`() {
    val onDisk = mapOf("мой" to plan("старое", 999))
    val merged = PlanMerge.merge(onDisk, mine = emptyMap(), ownThread = "мой", ownPlan = plan("новое", 1))
    assertEquals("новое", merged.getValue("мой").steps.single().content)
  }

  @Test
  fun `для чужих потоков побеждает более свежая отметка времени`() {
    val onDisk = mapOf("чужой" to plan("из файла", 100))
    val mine = mapOf("чужой" to plan("из памяти", 300))
    assertEquals("из памяти", PlanMerge.merge(onDisk, mine, "мой", plan("мой", 1)).getValue("чужой").steps.single().content)

    val older = mapOf("чужой" to plan("из памяти", 50))
    assertEquals("из файла", PlanMerge.merge(onDisk, older, "мой", plan("мой", 1)).getValue("чужой").steps.single().content)
  }

  @Test
  fun `при равных отметках побеждает файл`() {
    // Ничья — не повод перезаписывать чужую работу: тот писатель успел первым.
    val onDisk = mapOf("чужой" to plan("из файла", 100))
    val mine = mapOf("чужой" to plan("из памяти", 100))
    assertEquals("из файла", PlanMerge.merge(onDisk, mine, "мой", null).getValue("чужой").steps.single().content)
  }

  @Test
  fun `законченный план удаляется только свой`() {
    val onDisk = mapOf("мой" to plan("старое", 10), "чужой" to plan("их", 10))
    val merged = PlanMerge.merge(onDisk, mine = emptyMap(), ownThread = "мой", ownPlan = null)
    assertFalse("мой" in merged)
    assertTrue("чужой" in merged)
  }

  @Test
  fun `подрезка не выбрасывает план пишущего`() {
    val plans = (1..25).associate { "поток$it" to plan("шаг", it.toLong()) } + ("мой" to plan("мой", 0))
    val trimmed = PlanMerge.trim(plans, ownThread = "мой", limit = 20)
    assertEquals(20, trimmed.size)
    assertTrue("мой" in trimmed, "свой план — последнее, что можно выбросить: он живой прямо сейчас")
    assertTrue("поток25" in trimmed, "самые свежие остаются")
    assertFalse("поток1" in trimmed, "самые старые уходят первыми")
  }

  @Test
  fun `подрезка ничего не трогает, пока предел не достигнут`() {
    val plans = mapOf("a" to plan("a", 1), "b" to plan("b", 2))
    assertEquals(plans, PlanMerge.trim(plans, "a", limit = 20))
  }
}
