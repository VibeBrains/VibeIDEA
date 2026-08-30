// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.runs

/**
 * Running the same task again on a different model, and comparing the two runs.
 *
 * The question «а другая модель справилась бы лучше?» is normally answered by feeling, because the
 * two attempts happen days apart on different tasks. Here the task is identical by construction —
 * it is the recorded goal of the first run — so the comparison is about the model rather than about
 * how the request was phrased that time.
 *
 * What is compared is deliberately narrow: time, steps and files touched. «Качество» is not a number
 * and pretending otherwise would produce a verdict people would quote without checking.
 */
object RerunPlan {
  data class Comparison(
    val original: AgentRunLedger.Run,
    val repeat: AgentRunLedger.Run,
  ) {
    val originalMs: Long? get() = duration(original)
    val repeatMs: Long? get() = duration(repeat)

    /** Null when either run is still going: comparing a finished run to an unfinished one is noise. */
    val fasterRunId: String?
      get() {
        val a = originalMs ?: return null
        val b = repeatMs ?: return null
        return if (a == b) null else if (a < b) original.runId else repeat.runId
      }

    val sameOutcome: Boolean get() = original.status == repeat.status

    private fun duration(run: AgentRunLedger.Run): Long? =
      run.finishedAtMs?.let { (it - run.startedAtMs).coerceAtLeast(0) }
  }

  /** Only a finished run can be repeated: a running one is not a result to compare against. */
  fun canRepeat(run: AgentRunLedger.Run): Boolean = run.isFinished && run.goal.isNotBlank()

  /**
   * The task for the repeat, taken from the record rather than retyped: the whole point is that the
   * two runs got the SAME words.
   */
  fun repeatTask(run: AgentRunLedger.Run): String = run.goal

  /** Key that ties the repeat to its original, so a report can find the pair later. */
  fun repeatKey(original: AgentRunLedger.Run): String = "rerun-of-" + original.runId

  fun findRepeat(runs: List<AgentRunLedger.Run>, original: AgentRunLedger.Run): AgentRunLedger.Run? =
    runs.firstOrNull { it.idempotencyKey == repeatKey(original) && it.runId != original.runId }
}
