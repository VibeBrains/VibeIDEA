// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Waves: which steps run at once, and every reason two of them must not.
 */
class PipelineWavesTest {
  private val qa = RolePaths.Scope(allow = RolePaths.TEST_PATHS)

  private fun step(role: String, wave: String? = null, paths: List<String> = emptyList(), model: String? = null) =
    PipelineStep(role = role, task = "t", wave = wave, paths = paths, provider = model?.let { "p" }, model = model)

  private fun load(json: String): Pair<List<Pipeline>, List<String>> {
    val base = Files.createTempDirectory("vibe-waves-test")
    Files.createDirectories(base.resolve(".vibe"))
    Files.writeString(PipelinesFile.path(base.toString()), json)
    val warnings = ArrayList<String>()
    return PipelinesFile.load(base.toString()) { warnings.add(it) } to warnings
  }

  @Test
  fun `a wave is a run of neighbours with one label, a plain step is a run of one`() {
    val steps = listOf(step("backend-dev", "w1"), step("frontend-dev", "w1"), step("qa"),
                       step("code-reviewer", "w2"), step("security", "w2"), step("critic", "w2"))
    assertEquals(listOf(0..1, 2..2, 3..5), PipelineWaves.groups(steps))
  }

  @Test
  fun `writers in separate directories may run at once`() {
    val check = PipelineWaves.check(listOf(
      step("backend-dev", "build", listOf("server/**")),
      step("frontend-dev", "build", listOf("web/src/**", "web/public/")),
    ), qa)
    assertTrue(check.problems.isEmpty(), check.problems.toString())
  }

  @Test
  fun `writers whose places nest are refused`() {
    val check = PipelineWaves.check(listOf(
      step("backend-dev", "build", listOf("src/**")),
      step("frontend-dev", "build", listOf("src/ui/**")),
    ), qa)
    assertEquals(1, check.problems.size)
    assertTrue(check.problems.single().contains("src/**"))
  }

  @Test
  fun `a writer without paths writes anywhere and cannot share a wave with another writer`() {
    val check = PipelineWaves.check(listOf(step("backend-dev", "build"), step("frontend-dev", "build", listOf("web/**"))), qa)
    assertEquals(1, check.problems.size)
  }

  @Test
  fun `a pattern without a leading directory proves nothing`() {
    // `*.md` matches at any depth, so it overlaps every directory the other step owns.
    val check = PipelineWaves.check(listOf(
      step("designer", "docs", listOf("*.md")),
      step("backend-dev", "docs", listOf("server/**")),
    ), qa)
    assertEquals(1, check.problems.size)
  }

  @Test
  fun `qa without its own paths writes tests everywhere, so it cannot run beside a writer`() {
    val check = PipelineWaves.check(listOf(step("qa", "w"), step("backend-dev", "w", listOf("server/**"))), qa)
    assertEquals(1, check.problems.size)
  }

  @Test
  fun `judges never overlap, and a step on its own model writes nothing`() {
    val check = PipelineWaves.check(listOf(
      step("code-reviewer", "review"), step("security", "review"), step("critic", "review", model = "glm-5.3"),
    ), qa)
    assertTrue(check.problems.isEmpty())
    assertTrue(check.warnings.isEmpty())
  }

  @Test
  fun `a judge beside a writer is allowed but said out loud`() {
    val check = PipelineWaves.check(listOf(step("backend-dev", "w", listOf("server/**")), step("code-reviewer", "w")), qa)
    assertTrue(check.problems.isEmpty())
    assertEquals(1, check.warnings.size)
  }

  @Test
  fun `escalation and offPeak cannot be part of a wave`() {
    val escalation = PipelineWaves.check(listOf(step("code-reviewer", "w"), step("critic", "w").copy(escalation = true)), qa)
    assertEquals(1, escalation.problems.size)
    val offPeak = PipelineWaves.check(listOf(step("code-reviewer", "w"), step("critic", "w", model = "glm-5.3").copy(offPeak = true)), qa)
    assertEquals(1, offPeak.problems.size)
  }

  @Test
  fun `a label that comes back after another step is refused`() {
    val check = PipelineWaves.check(listOf(
      step("code-reviewer", "w"), step("security", "w"), step("planner"), step("critic", "w"), step("explore", "w"),
    ), qa)
    assertEquals(1, check.problems.size)
  }

  @Test
  fun `a wave of one step runs as an ordinary step, with a word about it`() {
    val check = PipelineWaves.check(listOf(step("backend-dev", "alone"), step("qa")), qa)
    assertTrue(check.problems.isEmpty())
    assertEquals(1, check.warnings.size)
  }

  @Test
  fun `a step of a wave starts in its own session`() {
    val (pipelines, _) = load("""
      { "pipelines": [ { "id": "p", "steps": [
        { "role": "backend-dev", "task": "api", "wave": "build", "paths": ["server/**"] },
        { "role": "frontend-dev", "task": "ui", "wave": "build", "paths": ["web/**"] }
      ] } ] }
    """.trimIndent())
    assertEquals(listOf(StepContext.FRESH, StepContext.FRESH), pipelines.single().steps.map { it.context })
    assertEquals(listOf("build", "build"), pipelines.single().steps.map { it.wave })
  }

  @Test
  fun `a shared step in a wave drops the pipeline with the reason`() {
    val (pipelines, warnings) = load("""
      { "pipelines": [ { "id": "p", "steps": [
        { "role": "code-reviewer", "task": "a", "wave": "w", "context": "shared" },
        { "role": "security", "task": "b", "wave": "w" }
      ] } ] }
    """.trimIndent())
    assertTrue(pipelines.isEmpty())
    assertTrue(warnings.any { it.contains("shared") })
  }

  @Test
  fun `an overlapping wave drops its pipeline and names it`() {
    val (pipelines, warnings) = load("""
      { "pipelines": [
        { "id": "bad", "steps": [
          { "role": "backend-dev", "task": "a", "wave": "w" },
          { "role": "frontend-dev", "task": "b", "wave": "w" }
        ] },
        { "id": "good", "steps": [ { "role": "planner", "task": "c" } ] }
      ] }
    """.trimIndent())
    assertEquals(listOf("good"), pipelines.map { it.id })
    assertTrue(warnings.any { it.contains("«bad»") })
  }

  @Test
  fun `a drafted plan passes the same wave check as the file`() {
    val plan = PipelinesFile.planFromAnswer("""{"steps":[
      {"role":"backend-dev","task":"a","wave":"w"},
      {"role":"frontend-dev","task":"b","wave":"w"}
    ]}""")
    assertIs<PipelinesFile.Plan.Refused>(plan)
  }

  @Test
  fun `a step outside any wave keeps its role's context`() {
    val (pipelines, _) = load("""{ "pipelines": [ { "id": "p", "steps": [ { "role": "backend-dev", "task": "a" } ] } ] }""")
    assertEquals(StepContext.SHARED, pipelines.single().steps.single().context)
    assertNull(pipelines.single().steps.single().wave)
  }
}
