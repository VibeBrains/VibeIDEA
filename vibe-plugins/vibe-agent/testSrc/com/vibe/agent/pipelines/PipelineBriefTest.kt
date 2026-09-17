// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The run's brief — the person's words kept whole — and the step that judges against them. */
class PipelineBriefTest {
  private val pipeline = Pipeline(
    id = "feature",
    name = "Фича под ключ",
    description = "Спроектировать, реализовать, проверить",
    steps = listOf(
      PipelineStep(role = "planner", task = "Разбей задачу на шаги"),
      PipelineStep(role = "backend-dev", task = "Реализуй по плану", acceptance = "тесты зелёные"),
      PipelineStep(role = "critic", task = "Сверь результат с задачей", againstBrief = true),
    ),
  )

  @Test
  fun `the brief keeps the name, the description and every task as written`() {
    val brief = PipelineBrief.of(pipeline)
    assertTrue(brief.startsWith("# Фича под ключ"), brief)
    assertTrue("Спроектировать, реализовать, проверить" in brief)
    assertTrue("1. [planner] Разбей задачу на шаги" in brief)
    assertTrue("2. [backend-dev] Реализуй по плану" in brief && "тесты зелёные" in brief)
    assertTrue("3. [critic] Сверь результат с задачей" in brief)
    // Nothing of the run itself: the brief is what was asked, not what happened.
    assertFalse("STATUS" in brief || "handoff" in brief)
  }

  private fun step(json: String) = PipelinesFile.parseStep(Json.parseToJsonElement(json).jsonObject, emptyMap()) {}

  @Test
  fun `againstBrief is read for a judging role and refused for one that writes`() {
    assertTrue(step("""{"role":"critic","task":"сверь","againstBrief":true}""").againstBrief)
    assertFalse(step("""{"role":"critic","task":"сверь"}""").againstBrief)
    assertFailsWith<IllegalArgumentException> { step("""{"role":"backend-dev","task":"сделай","againstBrief":true}""") }
    // `qa` judges too, but it may write tests — acceptance that can edit what it accepts is not acceptance.
    assertFailsWith<IllegalArgumentException> { step("""{"role":"qa","task":"сверь","againstBrief":true}""") }
  }

  @Test
  fun `a run folder name never leaves its own directory`() {
    assertEquals("run-12", RunFolder.safe("run-12"))
    assertEquals("etcpasswd", RunFolder.safe("../../etc/passwd"))
    assertEquals("run", RunFolder.safe("../.."))
  }
}
