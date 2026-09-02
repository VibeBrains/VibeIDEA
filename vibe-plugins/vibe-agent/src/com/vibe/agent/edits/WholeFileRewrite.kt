// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.edits

/**
 * Noticing when a model rewrote a whole file to change three lines.
 *
 * Anthropic names this as a behaviour change in Claude Fable 5.1: «the model is more likely to
 * rewrite the entire file than make a targeted edit… the rewrite costs more output tokens and
 * time». The result is usually correct, which is exactly why nobody notices — the file is right,
 * the diff is enormous, and the bill arrives at the end of the month.
 *
 * We do not block it: a rewrite that produces the right file is not a defect, and refusing it
 * would break work over a style preference. We say it out loud once, with the number, because a
 * cost nobody can see is a cost nobody can decide about.
 *
 * Pure: two strings in, a verdict out. Line-based, because that is the unit both the person and
 * the diff view think in.
 */
object WholeFileRewrite {
  /**
   * Below this share of changed lines a full rewrite is worth mentioning.
   *
   * A fifth is deliberately low: files legitimately get reshaped, and a warning that fires on
   * every real refactor is a warning people learn to scroll past.
   */
  const val NOTABLE_CHANGE_RATIO = 0.2

  /** Files shorter than this are cheap to rewrite whole; saying anything about them is noise. */
  const val MIN_LINES = 30

  data class Verdict(val changedLines: Int, val totalLines: Int) {
    val ratio: Double get() = if (totalLines == 0) 1.0 else changedLines.toDouble() / totalLines
  }

  /**
   * Was this write a whole-file rewrite for a small change?
   *
   * Null when there is nothing worth saying: a new file, a short one, or a genuinely large edit.
   * The comparison counts lines that differ in either direction, so a rewrite that only reorders
   * still counts as a change — reordering a file IS the expensive kind of rewrite.
   */
  fun check(oldText: String?, newText: String): Verdict? {
    if (oldText == null) return null
    val oldLines = oldText.lines()
    val newLines = newText.lines()
    if (oldLines.size < MIN_LINES) return null
    val changed = changedLineCount(oldLines, newLines)
    if (changed == 0) return null
    val verdict = Verdict(changed, maxOf(oldLines.size, newLines.size))
    return verdict.takeIf { it.ratio < NOTABLE_CHANGE_RATIO }
  }

  /**
   * How many lines differ, counted by content rather than by position.
   *
   * Position-based counting would call a one-line insertion at the top a total rewrite, which is
   * the opposite of what this is for: the question is «сколько строк действительно другие», and an
   * insertion leaves every other line intact.
   */
  private fun changedLineCount(oldLines: List<String>, newLines: List<String>): Int {
    val remaining = HashMap<String, Int>()
    for (line in oldLines) remaining[line] = (remaining[line] ?: 0) + 1
    var addedOrMoved = 0
    for (line in newLines) {
      val left = remaining[line] ?: 0
      if (left > 0) remaining[line] = left - 1 else addedOrMoved++
    }
    val removed = remaining.values.sum()
    return maxOf(addedOrMoved, removed)
  }
}
