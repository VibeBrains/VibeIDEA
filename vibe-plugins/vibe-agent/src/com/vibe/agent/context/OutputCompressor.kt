// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

/**
 * Long tool and command output, shrunk BEFORE it reaches the model — and recoverable in full.
 *
 * A test run that prints ten thousand lines says everything that matters in its first and last
 * dozen: the command, and the failure. The middle is scrollback, and it costs the same tokens as
 * the code the model still has to read afterwards.
 *
 * Two rules make this safe rather than merely cheap:
 * - the cut is ALWAYS announced in the text, with the number of lines removed and the handle to
 *   ask for them. Silent truncation is how a model concludes a test suite passed;
 * - the full text is kept as it was, so «покажи полностью» is an answer and not an apology.
 */
object OutputCompressor {
  /** Below this, compression saves nothing worth the marker line. */
  const val MIN_LINES_TO_COMPRESS = 60

  const val HEAD_LINES = 30
  const val TAIL_LINES = 20

  data class Result(
    val text: String,
    val compressed: Boolean,
    val droppedLines: Int,
    /** Identifies the stored full text; null when nothing was dropped. */
    val handle: String?,
  )

  /**
   * [marker] is rendered with `{dropped}` and `{handle}` already substituted by the caller's
   * catalogue — this object stays free of interface strings so it can be tested as arithmetic.
   */
  fun compress(text: String, handle: String, marker: (dropped: Int, handle: String) -> String,
               headLines: Int = HEAD_LINES, tailLines: Int = TAIL_LINES): Result {
    val lines = text.lines()
    if (lines.size < MIN_LINES_TO_COMPRESS || lines.size <= headLines + tailLines) {
      return Result(text, compressed = false, droppedLines = 0, handle = null)
    }
    val dropped = lines.size - headLines - tailLines
    val body = buildString {
      lines.take(headLines).forEach { appendLine(it) }
      appendLine(marker(dropped, handle))
      lines.takeLast(tailLines).forEachIndexed { index, line ->
        if (index == tailLines - 1) append(line) else appendLine(line)
      }
    }
    return Result(body, compressed = true, droppedLines = dropped, handle = handle)
  }

  /**
   * The full texts of this session's compressed outputs, newest first.
   *
   * Bounded on purpose: keeping every output of a long session forever would trade the token
   * cost for a memory leak, and the outputs people actually ask to see are the recent ones.
   */
  class Store(private val capacity: Int = 20) {
    private val entries = LinkedHashMap<String, String>()
    private var counter = 0

    @Synchronized
    fun put(fullText: String): String {
      val handle = "out-${++counter}"
      entries[handle] = fullText
      while (entries.size > capacity) entries.remove(entries.keys.first())
      return handle
    }

    @Synchronized
    fun get(handle: String): String? = entries[handle]

    @Synchronized
    fun size(): Int = entries.size
  }
}
