// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

/**
 * What changed since the last measurement of this page.
 *
 * A detector that only shows the current state answers «что сейчас плохо» — a question people stop
 * asking after the third time, because the answer is a long list they already decided to live with.
 * The question that keeps being worth asking is «что я только что сломал», and it needs the
 * previous measurement to exist.
 *
 * A finding is identified by rule plus selector plus viewport, not by its message: the message
 * carries the measured value («контраст 2.9:1»), and a contrast that drifted from 2.9 to 2.7 is the
 * same problem, not a new one. Counting it as new would fill the «появилось» list with noise on
 * every re-measure, and a list that is always long is a list nobody reads.
 *
 * Pure: two reports in, three lists out.
 */
object DesignDiff {
  data class Key(val rule: String, val selector: String, val viewport: Viewport)

  data class Result(
    /** Not there before — the list a person actually acts on. */
    val appeared: List<Finding>,
    /** Was there, is gone — worth saying, because fixing something deserves an answer. */
    val fixed: List<Finding>,
    /** Still there: known, decided about, not news. */
    val remained: List<Finding>,
  ) {
    val changed: Boolean get() = appeared.isNotEmpty() || fixed.isNotEmpty()

    /** Появившиеся нарушения пола — единственное, что стоит называть регрессией. */
    val floorAppeared: List<Finding> get() = appeared.filter { it.ruleClass == RuleClass.FLOOR && it.acceptedReason == null }
  }

  fun keyOf(finding: Finding): Key = Key(finding.rule, finding.selector, finding.viewport)

  /**
   * Compares two measurements of the same page.
   *
   * [previous] being null means «мерили впервые»: everything is «remained» rather than «appeared»,
   * because calling a first measurement a regression would teach people that the word means
   * nothing.
   */
  fun compare(previous: List<Finding>?, current: List<Finding>): Result {
    if (previous == null) return Result(emptyList(), emptyList(), current)
    val before = previous.associateBy(::keyOf)
    val now = current.associateBy(::keyOf)
    val appeared = current.filter { keyOf(it) !in before }
    val fixed = previous.filter { keyOf(it) !in now }
    val remained = current.filter { keyOf(it) in before }
    return Result(appeared, fixed, remained)
  }

  /**
   * One line for the chat, or null when nothing changed.
   *
   * Null rather than «изменений нет»: a measurement that produced the same list as before is the
   * normal case, and saying so every time trains people to skip the line that matters.
   */
  fun summary(result: Result, labels: Labels): String? {
    if (!result.changed) return null
    val parts = ArrayList<String>()
    if (result.appeared.isNotEmpty()) parts.add(labels.appeared(result.appeared.size))
    if (result.fixed.isNotEmpty()) parts.add(labels.fixed(result.fixed.size))
    return parts.joinToString(", ")
  }

  interface Labels {
    fun appeared(count: Int): String
    fun fixed(count: Int): String
  }
}
