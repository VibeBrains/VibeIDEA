// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

/**
 * The run's brief: what the person asked for, in their own words, before any agent retold it.
 *
 * The acceptance step used to judge against the artifacts and the previous step's summary — that is, against the
 * agent's own account of the work, which agrees with itself by construction. The brief is the only text in a run that
 * nobody rewrote: the pipeline's name, its description and the task of every step, exactly as written in
 * `.vibe/pipelines.json` (Autopilot's manifest, taken in the shape our runs actually have, 17.09.2026).
 *
 * Pure: a pipeline in, its brief out. Kept verbatim — a run that edits its own brief has no anchor left.
 */
object PipelineBrief {
  fun of(pipeline: Pipeline): String = buildString {
    appendLine("# " + pipeline.name)
    pipeline.description?.takeIf { it.isNotBlank() }?.let { appendLine(); appendLine(it) }
    appendLine()
    pipeline.steps.forEachIndexed { index, step ->
      appendLine("${index + 1}. [${step.role}] ${step.task}")
      step.acceptance?.takeIf { it.isNotBlank() }?.let { appendLine("   " + it) }
    }
  }
}
