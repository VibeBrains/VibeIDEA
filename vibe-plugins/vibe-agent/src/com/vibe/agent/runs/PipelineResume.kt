// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.runs

/**
 * Continuing an interrupted pipeline from the step it did not finish.
 *
 * A pipeline runs for minutes with nobody watching, and the window dies — an update, a crash, a
 * closed laptop. Until this existed the only option was to start from step one, paying again for
 * every step already done and re-running writers over files they had already changed.
 *
 * The rule is deliberately narrow. Only an ORPHANED or FAILED run of the SAME pipeline id resumes,
 * only when the pipeline still has as many steps as the run expected (an edited pipeline is a new
 * plan, and step 4 of the old one is not step 4 of the new), and only the latest such run: an older
 * interruption was already superseded by whatever ran after it.
 *
 * Pure: records in, the step index out.
 */
object PipelineResume {
  data class Point(val run: AgentRunLedger.Run, val fromStep: Int)

  fun find(runs: List<AgentRunLedger.Run>, pipelineId: String, stepCount: Int): Point? {
    val latest = runs.filter { it.source == AgentRunLedger.Source.PIPELINE && it.pipelineId == pipelineId }
      .maxByOrNull { it.startedAtMs } ?: return null
    if (latest.status != AgentRunLedger.Status.ORPHANED && latest.status != AgentRunLedger.Status.FAILED) return null
    if (latest.maxSteps != stepCount) return null
    val from = latest.steps
    if (from <= 0 || from >= stepCount) return null
    return Point(latest, from)
  }
}
