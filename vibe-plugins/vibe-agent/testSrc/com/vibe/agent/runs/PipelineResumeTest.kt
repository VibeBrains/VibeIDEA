// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.runs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
