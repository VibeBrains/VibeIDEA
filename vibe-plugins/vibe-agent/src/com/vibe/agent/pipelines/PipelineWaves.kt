// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

import com.vibe.agent.i18n.VibeI18n.t

/**
 * Waves: steps that follow each other with the same `wave` label and run at once.
 *
 * Running at once is safe only for steps that cannot step on each other, and every rule here guards one way they
 * could:
 * - the label must not come back after another step — a wave is one run of steps, not a set scattered over the file;
 * - `escalation` needs the previous step's gate verdict, and in a wave there is no previous step;
 * - `offPeak` holds a step until its model's price drops, which would start the wave's steps at different times;
 * - two steps that write must write to provably separate places ([RolePaths.provablyDisjoint]): each write is checked
 *   against its own step's boundary only, and two steps allowed the same file would race for it.
 *
 * Pure: steps in, verdicts out.
 */
object PipelineWaves {
  /** What stops a pipeline from running ([problems]) and what is merely worth saying ([warnings]). */
  data class Check(val problems: List<String>, val warnings: List<String>)

  /**
   * The runs of [steps] in order: a wave, or a single step on its own. Indices into [steps].
   *
   * A label on one step alone makes a run of one — it runs as an ordinary step.
   */
  fun groups(steps: List<PipelineStep>): List<IntRange> {
    val runs = ArrayList<IntRange>()
    var start = 0
    while (start < steps.size) {
      val wave = steps[start].wave
      var end = start
      if (wave != null) while (end + 1 < steps.size && steps[end + 1].wave == wave) end++
      runs += start..end
      start = end + 1
    }
    return runs
  }

  /**
   * Whether the waves of [steps] can run at once. [qa] is the project's boundary for the `qa` role, which a qa step
   * without its own `paths` writes under.
   */
  fun check(steps: List<PipelineStep>, qa: RolePaths.Scope): Check {
    val problems = ArrayList<String>()
    val warnings = ArrayList<String>()
    val finished = HashSet<String>()
    for (run in groups(steps)) {
      val members = run.map { steps[it] }
      val wave = members.first().wave ?: continue
      if (!finished.add(wave)) problems += t("pipeline.warn.waveSplit", "wave" to wave)
      if (members.size == 1) {
        warnings += t("pipeline.warn.waveSingle", "wave" to wave)
        continue
      }
      members.firstOrNull { it.escalation }?.let { problems += t("pipeline.warn.waveEscalation", "wave" to wave, "role" to it.role) }
      members.firstOrNull { it.offPeak }?.let { problems += t("pipeline.warn.waveOffPeak", "wave" to wave, "role" to it.role) }
      val writers = members.filter { writes(it) }
      for (i in writers.indices) {
        for (j in i + 1 until writers.size) {
          val first = writers[i]
          val second = writers[j]
          if (!RolePaths.provablyDisjoint(scopeOf(first, qa), scopeOf(second, qa))) {
            problems += t("pipeline.warn.waveOverlap", "wave" to wave, "first" to first.role, "second" to second.role,
                          "firstPaths" to describe(scopeOf(first, qa)), "secondPaths" to describe(scopeOf(second, qa)))
          }
        }
      }
      // A judge next to a writer reads the writer's work while it is being written: allowed, but said out loud.
      val judge = members.firstOrNull { !writes(it) }
      if (judge != null && writers.isNotEmpty()) {
        warnings += t("pipeline.warn.waveJudgeBesideWriter", "wave" to wave, "judge" to judge.role, "writer" to writers.first().role)
      }
    }
    return Check(problems, warnings)
  }

  /** Does the step write files: a writing role on the pipeline's agent — a step on its own model writes nothing. */
  fun writes(step: PipelineStep): Boolean = step.model == null && RoleRights.mayWrite(step.role)

  /** Where the step may write — the same boundary the write itself is checked against. */
  fun scopeOf(step: PipelineStep, qa: RolePaths.Scope): RolePaths.Scope =
    RolePaths.effective(step.role, RolePaths.Scope(step.paths, step.denyPaths), qa)

  private fun describe(scope: RolePaths.Scope): String = scope.allow.joinToString().ifEmpty { "**" }
}
