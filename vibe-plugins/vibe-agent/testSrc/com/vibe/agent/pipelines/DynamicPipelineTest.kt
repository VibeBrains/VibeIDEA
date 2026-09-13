// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A dynamic pipeline: the orchestrator drafts, the same parser reads the draft, a person approves.
 * The tests cover the two pure halves — the file shape and the draft.
 */
class DynamicPipelineTest {
  private fun load(json: String): Pair<List<Pipeline>, List<String>> {
    val base = Files.createTempDirectory("dynamic")
    try {
      val file = PipelinesFile.path(base.toString())
      Files.createDirectories(file.parent)
      Files.writeString(file, json)
      val warnings = ArrayList<String>()
      return PipelinesFile.load(base.toString()) { warnings.add(it) } to warnings
    }
    finally {
      base.toFile().deleteRecursively()
    }
  }

  @Test
  fun `a dynamic pipeline is one orchestrator step`() {
    val (pipelines, warnings) = load("""{"pipelines":[{"id":"auto","dynamic":true,"steps":[{"role":"orchestrator","task":"Разбей задачу"}]}]}""")
    assertTrue(warnings.isEmpty(), warnings.toString())
    assertTrue(pipelines.single().dynamic)
  }

  @Test
  fun `any other shape is refused`() {
    val (pipelines, warnings) = load("""{"pipelines":[{"id":"auto","dynamic":true,"steps":[{"role":"planner","task":"x"},{"role":"backend-dev","task":"y"}]}]}""")
    assertTrue(pipelines.isEmpty())
    assertEquals(1, warnings.size)
  }

  @Test
  fun `a draft in prose is read by the file's own rules`() {
    val answer = """
      Вот план:
      {"steps": [
        {"role": "explore", "task": "Найди, где авторизация"},
        {"role": "backend-dev", "task": "Добавь проверку", "acceptance": "тесты зелёные"},
        {"role": "code-reviewer", "task": "Проверь", "model": "anthropic/claude-opus-5"}
      ]}
      Готов начать по согласию.
    """.trimIndent()
    val plan = assertIs<PipelinesFile.Plan.Ok>(PipelinesFile.planFromAnswer(answer))
    assertEquals(listOf("explore", "backend-dev", "code-reviewer"), plan.steps.map { it.role })
    assertEquals("anthropic", plan.steps[2].provider)
  }

  @Test
  fun `a draft breaking the file's rules is refused, not trimmed`() {
    assertIs<PipelinesFile.Plan.Refused>(PipelinesFile.planFromAnswer("""{"steps":[{"role":"backend-dev","task":"x","model":"zai/glm-5.3-flash"}]}"""))
    assertIs<PipelinesFile.Plan.Refused>(PipelinesFile.planFromAnswer("""{"steps":[{"role":"wizard","task":"x"}]}"""))
  }

  @Test
  fun `a plan cannot contain an orchestrator`() {
    assertIs<PipelinesFile.Plan.Refused>(PipelinesFile.planFromAnswer("""{"steps":[{"role":"orchestrator","task":"ещё план"}]}"""))
  }

  @Test
  fun `no JSON, or no steps, is no plan`() {
    assertIs<PipelinesFile.Plan.Refused>(PipelinesFile.planFromAnswer("Сначала изучу проект, потом решу."))
    assertIs<PipelinesFile.Plan.Refused>(PipelinesFile.planFromAnswer("""{"plan":"нет шагов"}"""))
    assertIs<PipelinesFile.Plan.Refused>(PipelinesFile.planFromAnswer("""{"steps":[]}"""))
  }
}
