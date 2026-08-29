// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

/**
 * Reading `.vibe/audit.jsonl` as it grows, instead of re-reading it whole.
 *
 * The log rotates at ten megabytes, and a viewer that re-reads the file on every tick would spend
 * more time parsing than the agent spends working. So the panel remembers where it stopped and asks
 * only for what was appended.
 *
 * The interesting part is not the offset but ROTATION: when the file becomes shorter than the
 * offset, or its identity changes, the offset points into the middle of a different file. Continuing
 * from it would render garbage — the honest answer is to start over and say so.
 *
 * Pure: the arithmetic lives here, the file handle does not.
 */
object AuditTail {
  data class Position(val offset: Long, val fileKey: String)

  sealed interface Step {
    /** Read from [from] to the end; the log only grew. */
    data class Append(val from: Long) : Step

    /** The file was rotated or truncated: read from the beginning and tell the user why. */
    data object Restart : Step

    /** Nothing changed. */
    data object Idle : Step
  }

  /**
   * @param fileKey something that changes when the file is replaced — inode, creation time, name of
   *        the rotated archive. Size alone is not enough: a rotation that lands on exactly the same
   *        size would go unnoticed.
   */
  fun next(previous: Position?, currentSize: Long, currentKey: String): Step = when {
    previous == null -> Step.Restart
    previous.fileKey != currentKey -> Step.Restart
    currentSize < previous.offset -> Step.Restart
    currentSize == previous.offset -> Step.Idle
    else -> Step.Append(previous.offset)
  }

  /**
   * Splits an appended chunk into complete lines, returning the leftover.
   *
   * A tail read can land mid-line: the agent writes a record while we read. Parsing that half would
   * produce a broken entry that never repairs itself, so the remainder is carried to the next tick.
   */
  fun completeLines(chunk: String): Pair<List<String>, String> {
    val lastBreak = chunk.lastIndexOf('\n')
    if (lastBreak < 0) return emptyList<String>() to chunk
    val complete = chunk.substring(0, lastBreak).lines().filter { it.isNotBlank() }
    return complete to chunk.substring(lastBreak + 1)
  }

  /** Keeps the view bounded: an agent working for hours must not turn the panel into a memory leak. */
  fun trim(lines: List<String>, max: Int): List<String> =
    if (lines.size <= max) lines else lines.takeLast(max)

  const val MAX_VIEW_LINES = 2_000
}
