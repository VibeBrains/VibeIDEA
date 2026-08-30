// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.council

/**
 * One question asked of several DIFFERENT models, each blind to the others, then reconciled.
 *
 * The point is the difference between them. The same model asked twice agrees with itself — it is
 * the same weights reaching the same conclusion, and the second answer reads as confirmation while
 * being nothing of the kind. Two models that were trained differently disagreeing is information:
 * it says the question is genuinely open, or that one of them knows something the other does not.
 *
 * So the plan refuses duplicates, and a council of one is not a council.
 */
object CouncilPlan {
  /** `providerId/modelId`, the shape used everywhere else in the settings. */
  data class Adviser(val providerId: String, val modelId: String) {
    override fun toString(): String = "$providerId/$modelId"
  }

  data class Plan(val advisers: List<Adviser>, val problems: List<String> = emptyList()) {
    val isUsable: Boolean get() = advisers.size >= MIN_ADVISERS
  }

  const val MIN_ADVISERS = 2
  const val MAX_ADVISERS = 5

  /**
   * [spec] is a comma- or newline-separated list. Malformed entries are reported rather than
   * dropped in silence: a council quietly running with one adviser is worse than no council,
   * because its answer LOOKS like agreement.
   */
  fun parse(spec: String, unknownProvider: (String) -> Boolean = { false }): Plan {
    val problems = ArrayList<String>()
    val advisers = LinkedHashSet<Adviser>()
    for (raw in spec.split(',', '\n')) {
      val entry = raw.trim()
      if (entry.isEmpty()) continue
      val slash = entry.indexOf('/')
      if (slash <= 0 || slash == entry.length - 1) {
        problems.add(entry)
        continue
      }
      val adviser = Adviser(entry.substring(0, slash).trim(), entry.substring(slash + 1).trim())
      if (unknownProvider(adviser.providerId)) {
        problems.add(entry)
        continue
      }
      if (!advisers.add(adviser)) continue
      if (advisers.size >= MAX_ADVISERS) break
    }
    return Plan(advisers.toList(), problems)
  }

  /** Every adviser is asked the SAME text: a difference in the question is not a difference of opinion. */
  fun adviserPrompt(question: String, instruction: String): String = "$instruction\n\n$question"

  /**
   * The synthesis prompt. The answers are numbered rather than attributed by model name on purpose:
   * a judge that knows which answer came from the famous model agrees with the famous model.
   */
  fun synthesisPrompt(question: String, answers: List<String>, instruction: String): String = buildString {
    appendLine(instruction)
    appendLine()
    appendLine(question)
    answers.forEachIndexed { index, answer ->
      appendLine()
      appendLine("--- ${index + 1} ---")
      appendLine(answer.trim())
    }
  }.trimEnd()
}
