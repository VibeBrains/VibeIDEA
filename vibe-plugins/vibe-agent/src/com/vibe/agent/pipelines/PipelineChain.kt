// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.pipelines

/**
 * A pipeline as one line of text: the roles of its steps in order, steps of a wave side by side, a step's own model as a mark
 *
 * Roles are named as everywhere else in the project: by the id written in the file
 * Pure: steps in, tokens out, so the row can paint the model mark small and the tooltip can print the same line whole
 */
object PipelineChain {
  enum class Kind { ROLE, MODEL, LINK }

  data class Token(val text: String, val kind: Kind)

  /** Between consecutive runs: the next one starts when the previous has finished */
  const val AFTER = " → "

  /** Between the steps of one wave: they run at once */
  const val ALONGSIDE = " ∥ "

  /** The model of a step in the short form, without the provider; null — the step takes its agent's or its role's model */
  fun modelName(step: PipelineStep): String? =
    step.model?.trim()?.substringAfterLast('/')?.ifEmpty { null }

  fun tokens(steps: List<PipelineStep>): List<Token> {
    val result = ArrayList<Token>()
    for ((run, range) in PipelineWaves.groups(steps).withIndex()) {
      if (run > 0) result += Token(AFTER, Kind.LINK)
      for (index in range) {
        if (index > range.first) result += Token(ALONGSIDE, Kind.LINK)
        val step = steps[index]
        result += Token(step.role, Kind.ROLE)
        modelName(step)?.let { result += Token(" ($it)", Kind.MODEL) }
      }
    }
    return result
  }

  fun text(steps: List<PipelineStep>): String = tokens(steps).joinToString("") { it.text }
}
