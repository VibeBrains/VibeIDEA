// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

import com.vibe.agent.context.ContextBudget

/**
 * How much of a run's diff a judge on its own model gets.
 *
 * Such a judge has no tools: without the diff it judged the author's account of the work — the
 * paths and the tail of an answer — not the work (research 11.09.2026, decision №80). All of the
 * diff when it fits; otherwise its head, cut at a line, and a note: a judge that cannot fetch the
 * rest must at least know there is a rest.
 */
object JudgeDiff {
  /** About ten thousand tokens: a review-sized change. A larger one is a run to review in parts. */
  const val MAX_CHARS = 40_000

  data class Fitted(val text: String, val truncated: Boolean)

  /**
   * @param maxTokens the step's own ceiling, if any: the diff takes at most half of it, so that the
   *   answer still fits under the same ceiling.
   */
  fun fit(diff: String, maxTokens: Int?): Fitted {
    val budget = maxTokens?.takeIf { it > 0 }
      ?.let { minOf(MAX_CHARS.toLong(), it.toLong() * ContextBudget.CHARS_PER_TOKEN / 2).toInt() }
      ?: MAX_CHARS
    if (diff.length <= budget) return Fitted(diff, truncated = false)
    // At a line boundary: half a hunk header misleads more than a missing hunk.
    val cut = diff.lastIndexOf('\n', budget).takeIf { it > 0 } ?: budget
    return Fitted(diff.substring(0, cut), truncated = true)
  }
}
