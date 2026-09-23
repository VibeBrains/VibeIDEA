// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.runs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PipelineResumeTest {
  private fun run(status: AgentRunLedger.Status, steps: Int, max: Int = 4, id: String = "feature", at: Long = 1) =
    AgentRunLedger.Run(runId = "r$at", epoch = "e", source = AgentRunLedger.Source.PIPELINE, goal = "g",
                       status = status, target = null, startedAtMs = at, steps = steps, maxSteps = max, pipelineId = id)

  @Test
  fun `an orphaned run resumes after its last finished step`() {
    assertEquals(2, PipelineResume.find(listOf(run(AgentRunLedger.Status.ORPHANED, 2)), "feature", 4)?.fromStep)
  }

  @Test
  fun `a failed run resumes too`() {
    assertEquals(3, PipelineResume.find(listOf(run(AgentRunLedger.Status.FAILED, 3)), "feature", 4)?.fromStep)
  }

  @Test
  fun `completed, running and cancelled runs do not resume`() {
    for (status in listOf(AgentRunLedger.Status.COMPLETED, AgentRunLedger.Status.RUNNING, AgentRunLedger.Status.CANCELLED)) {
      assertNull(PipelineResume.find(listOf(run(status, 2)), "feature", 4), status.name)
    }
  }

  @Test
  fun `an edited pipeline is a new plan`() {
    assertNull(PipelineResume.find(listOf(run(AgentRunLedger.Status.ORPHANED, 2, max = 4)), "feature", 5))
  }

  @Test
  fun `nothing to resume before the first step or after the last`() {
    assertNull(PipelineResume.find(listOf(run(AgentRunLedger.Status.ORPHANED, 0)), "feature", 4))
    assertNull(PipelineResume.find(listOf(run(AgentRunLedger.Status.FAILED, 4)), "feature", 4))
  }

  @Test
  fun `only the latest run of that pipeline counts`() {
    val older = run(AgentRunLedger.Status.ORPHANED, 2, at = 1)
    val newer = run(AgentRunLedger.Status.COMPLETED, 4, at = 2)
    assertNull(PipelineResume.find(listOf(older, newer), "feature", 4))
  }

  @Test
  fun `another pipeline id never resumes this one`() {
    assertNull(PipelineResume.find(listOf(run(AgentRunLedger.Status.ORPHANED, 2, id = "other")), "feature", 4))
  }

  @Test
  fun `pipeline id survives the ledger codec`() {
    val decoded = AgentRunLedger.decode(AgentRunLedger.encode(run(AgentRunLedger.Status.ORPHANED, 2)))
    assertEquals("feature", decoded?.pipelineId)
  }

  @Test
  fun `a run interrupted mid-wave resumes at the step that did not finish, skipping the one after it that did`() {
    // Steps 3 and 4 were a wave; 4 finished first, and the window died before 3 did.
    val interrupted = run(AgentRunLedger.Status.ORPHANED, 3, max = 5).copy(done = listOf(0, 1, 3))
    val point = PipelineResume.find(listOf(interrupted), "feature", 5)
    assertEquals(2, point?.fromStep)
    assertEquals(setOf(0, 1, 3), point?.done)
  }

  @Test
  fun `finished steps out of order survive the ledger codec, and a plain prefix is not written twice`() {
    val wave = run(AgentRunLedger.Status.ORPHANED, 3, max = 5).copy(done = listOf(3, 0, 1))
    val line = AgentRunLedger.encode(wave)
    assertEquals(listOf(0, 1, 3), AgentRunLedger.decode(line)?.done)
    val prefix = AgentRunLedger.encode(run(AgentRunLedger.Status.ORPHANED, 2).copy(done = listOf(0, 1)))
    assertFalse(prefix.contains("\"done\""), "an ordinary record keeps its count alone")
    assertTrue(AgentRunLedger.decode(prefix)?.doneSteps == setOf(0, 1))
  }
}
