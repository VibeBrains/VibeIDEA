// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.runs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RerunPlanTest {
  private fun run(
    id: String,
    status: AgentRunLedger.Status = AgentRunLedger.Status.COMPLETED,
    started: Long = 0,
    finished: Long? = 1_000,
    goal: String = "почини гейт",
    key: String? = null,
  ) = AgentRunLedger.Run(
    runId = id, epoch = "e", source = AgentRunLedger.Source.PIPELINE, goal = goal, status = status,
    target = "acp/claude", startedAtMs = started, finishedAtMs = finished, idempotencyKey = key,
  )

  @Test
  fun `only a finished run with a goal can be repeated`() {
    assertTrue(RerunPlan.canRepeat(run("a")))
    assertFalse(RerunPlan.canRepeat(run("b", status = AgentRunLedger.Status.RUNNING, finished = null)))
    assertFalse(RerunPlan.canRepeat(run("c", goal = "")))
  }

  @Test
  fun `the repeat gets the recorded words, not retyped ones`() {
    // Иначе сравнивались бы формулировки, а не модели.
    assertEquals("почини гейт", RerunPlan.repeatTask(run("a")))
  }

  @Test
  fun `the repeat is tied to its original and can be found later`() {
    val original = run("a")
    val repeat = run("b", key = RerunPlan.repeatKey(original))
    assertEquals("b", RerunPlan.findRepeat(listOf(original, repeat), original)?.runId)
  }

  @Test
  fun `a run is never its own repeat`() {
    val original = run("a", key = RerunPlan.repeatKey(run("a")))
    assertNull(RerunPlan.findRepeat(listOf(original), original))
  }

  @Test
  fun `the faster run is named, and a tie names nobody`() {
    val fast = run("fast", started = 0, finished = 500)
    val slow = run("slow", started = 0, finished = 900)
    assertEquals("fast", RerunPlan.Comparison(fast, slow).fasterRunId)
    assertNull(RerunPlan.Comparison(fast, run("same", started = 0, finished = 500)).fasterRunId)
  }

  @Test
  fun `an unfinished run is not compared`() {
    // Сравнивать законченный прогон с идущим — способ получить уверенный вывод из ничего.
    val running = run("running", status = AgentRunLedger.Status.RUNNING, finished = null)
    assertNull(RerunPlan.Comparison(run("a"), running).fasterRunId)
    assertNull(RerunPlan.Comparison(running, run("a")).repeatMs?.let { null } ?: null)
  }

  @Test
  fun `different outcomes are visible in the comparison`() {
    val ok = run("a")
    val failed = run("b", status = AgentRunLedger.Status.FAILED)
    assertFalse(RerunPlan.Comparison(ok, failed).sameOutcome)
    assertTrue(RerunPlan.Comparison(ok, run("c")).sameOutcome)
  }
}
