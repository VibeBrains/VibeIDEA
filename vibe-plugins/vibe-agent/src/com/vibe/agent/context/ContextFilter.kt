// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

/**
 * Intermediate tool output, squeezed BEFORE it becomes part of the conversation.
 *
 * [OutputCompressor] cuts the middle out of one long output. This does the other half: it removes
 * what carries no information at all — a progress bar redrawn four hundred times, the same warning
 * repeated for every file, a wall of blank lines. That noise is not merely large, it is actively
 * harmful: it teaches the model that repetition is what the tool is for, and it pushes the actual
 * error out of the window.
 *
 * Modes, because one policy cannot fit both `npm install` and a diff:
 * - RAW keeps everything (a tool whose output IS the answer);
 * - AUTO collapses runs of identical lines and drops pure-noise ones;
 * - AGGREGATE keeps only the shape: counts by line pattern, for output nobody reads line by line.
 */
object ContextFilter {
  enum class Mode { RAW, AUTO, AGGREGATE, OFF }

  data class Result(val text: String, val removedLines: Int, val mode: Mode)

  /** A line that says nothing on its own: progress bars, spinners, separators, blanks. */
  fun isNoise(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return true
    // A line made of one repeated punctuation char is a separator or a progress bar.
    val distinct = trimmed.toSet()
    if (distinct.size <= 2 && trimmed.length >= 4 && distinct.all { !it.isLetterOrDigit() }) return true
    if (PROGRESS.matches(trimmed)) return true
    return false
  }

  fun filter(text: String, mode: Mode, collapseAfter: Int = COLLAPSE_AFTER, repeatMark: (Int) -> String = { "… ×$it" }): Result {
    if (mode == Mode.RAW || mode == Mode.OFF || text.isEmpty()) return Result(text, 0, mode)
    val lines = text.lines()
    if (mode == Mode.AGGREGATE) return aggregate(lines, repeatMark)

    val out = ArrayList<String>(lines.size)
    var removed = 0
    var previous: String? = null
    var run = 0
    for (line in lines) {
      if (isNoise(line)) { removed++; continue }
      if (line == previous) {
        run++
        // The first repetition is kept: «то же самое ещё раз» is information, the four hundredth is not.
        if (run >= collapseAfter) { removed++; continue }
      }
      else {
        if (run >= collapseAfter) out.add(repeatMark(run))
        run = 0
        previous = line
      }
      out.add(line)
    }
    if (run >= collapseAfter) out.add(repeatMark(run))
    return Result(out.joinToString("\n"), removed, mode)
  }

  /** Keeps the SHAPE: how many times each line occurred, most frequent first. */
  private fun aggregate(lines: List<String>, repeatMark: (Int) -> String): Result {
    val meaningful = lines.filterNot { isNoise(it) }
    val counts = meaningful.groupingBy { it.trim() }.eachCount()
    val text = counts.entries.sortedByDescending { it.value }.joinToString("\n") { (line, count) ->
      if (count > 1) "$line  ${repeatMark(count)}" else line
    }
    return Result(text, lines.size - counts.size, Mode.AGGREGATE)
  }

  fun modeOf(name: String?): Mode = when (name?.lowercase()) {
    "raw" -> Mode.RAW
    "aggregate" -> Mode.AGGREGATE
    "off" -> Mode.OFF
    else -> Mode.AUTO
  }

  const val COLLAPSE_AFTER = 2
  private val PROGRESS = Regex("^[\\d.]+%.*|^\\[[=>. #-]+\\]$|^[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏].*")
}
