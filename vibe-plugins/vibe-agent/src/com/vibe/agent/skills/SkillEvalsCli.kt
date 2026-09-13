// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

/**
 * The command line of `vibe-evals`: arguments in, options out; a report in, an exit code out.
 *
 * A gate on agent behaviour needs something a script can call and a number it can compare. The menu
 * action has neither — it asks a person and shows a dialog. Pure, so the contract a CI job relies on
 * is tested without starting an IDE.
 */
object SkillEvalsCli {
  const val COMMAND = "vibe-evals"

  data class Options(val projectPath: String, val skillId: String, val threshold: Double)

  sealed interface Parsed {
    data class Ok(val options: Options) : Parsed
    data class Error(val message: String) : Parsed
  }

  /** The run met the threshold. */
  const val EXIT_PASSED = 0

  /** The run finished below the threshold — the gate says «no». */
  const val EXIT_BELOW = 1

  /** The run could not happen: bad arguments, no cases, no model. Not the same as a failed run. */
  const val EXIT_ERROR = 2

  const val USAGE = "usage: vibe-evals <project-path> <skill-id> [--threshold 0..1]"

  /** [args] as the platform hands them: the command name first. */
  fun parse(args: List<String>): Parsed {
    val rest = args.dropWhile { it == COMMAND }
    val positional = ArrayList<String>()
    var threshold = 1.0
    var i = 0
    while (i < rest.size) {
      when (val arg = rest[i]) {
        "--threshold" -> {
          val value = rest.getOrNull(i + 1)?.toDoubleOrNull()
            ?: return Parsed.Error("--threshold needs a number between 0 and 1")
          if (value < 0.0 || value > 1.0) return Parsed.Error("--threshold must be between 0 and 1, got $value")
          threshold = value
          i++
        }
        else -> if (arg.startsWith("--")) return Parsed.Error("unknown option $arg") else positional += arg
      }
      i++
    }
    if (positional.size != 2) return Parsed.Error(USAGE)
    return Parsed.Ok(Options(positional[0], positional[1], threshold))
  }

  /**
   * Share of cases that passed. Unjudged cases count as not passed: a gate that let «nobody could say»
   * through would be green exactly when the judge is broken.
   */
  fun score(report: SkillEvalRun.Report): Double =
    if (report.results.isEmpty()) 0.0 else report.passed.toDouble() / report.results.size

  fun exitCode(report: SkillEvalRun.Report, threshold: Double): Int =
    if (report.results.isEmpty()) EXIT_ERROR else if (score(report) >= threshold) EXIT_PASSED else EXIT_BELOW
}
