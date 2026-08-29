// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.fim

/**
 * What to ask the model for at this caret — and whether to ask at all.
 *
 * The shape of a good completion depends on where the caret sits, and asking for the wrong shape
 * produces the two failures people actually notice: a closing bracket duplicated after the one
 * already there, and a wall of text where one line was wanted. Ported from VibeIDE's
 * `getCompletionOptions`, with one case we did not have at all — **не предсказывать**: a request
 * that can only produce a bad suggestion is not worth its latency or its money.
 *
 * Pure: takes the text around the caret, returns what to send.
 */
object FimPrediction {
  enum class Type {
    /** Just accepted a suggestion and the line ends here: continue on the next line. */
    MULTI_LINE_NEXT_LINE,

    /** Nothing around the caret on this line: fill it in, one line. */
    SINGLE_LINE_FILL,

    /** A stub of a tail (`)`, `;`): let the model rewrite it instead of appending to it. */
    SINGLE_LINE_REDO_SUFFIX,

    /** Caret in the middle of a line with something typed to its left. */
    SINGLE_LINE_MIDDLE,

    /** Nothing worth asking for: the model has no prefix to build on, the answer would be noise. */
    DO_NOT_PREDICT,
  }

  data class Plan(
    val type: Type,
    val prefix: String,
    val suffix: String,
    val stop: List<String>,
  ) {
    val shouldGenerate: Boolean get() = type != Type.DO_NOT_PREDICT
  }

  /** Lines of context sent to the model: a local model pays for every token in latency. */
  const val CONTEXT_LINES_CLOUD = 25
  const val CONTEXT_LINES_LOCAL = 12

  /** A tail this short is a stub the model should rewrite, not append to. */
  private const val TRIVIAL_SUFFIX_CHARS = 3

  private val LINE_BREAKS = listOf("\n", "\r\n", "\r")

  /**
   * @param prefix text before the caret (already limited to the context window)
   * @param suffix text after the caret (same)
   * @param lineBeforeCaret the part of the current line to the left of the caret
   * @param lineAfterCaret the part of the current line to the right of the caret
   * @param justAccepted the previous suggestion was accepted a moment ago
   */
  fun plan(
    prefix: String,
    suffix: String,
    lineBeforeCaret: String,
    lineAfterCaret: String,
    justAccepted: Boolean,
  ): Plan {
    val lineEmpty = lineBeforeCaret.isBlank() && lineAfterCaret.isBlank()
    val prefixEmpty = lineBeforeCaret.filterNot { it.isWhitespace() }.isEmpty()
    val suffixEmpty = lineAfterCaret.filterNot { it.isWhitespace() }.isEmpty()
    val suffixTail = lineAfterCaret.filterNot { it.isWhitespace() }

    return when {
      // Continue the thought that was just accepted, from the next line down.
      justAccepted && suffixEmpty ->
        Plan(Type.MULTI_LINE_NEXT_LINE, prefix + "\n", suffix, listOf("\n\n"))

      lineEmpty ->
        Plan(Type.SINGLE_LINE_FILL, prefix, suffix, LINE_BREAKS)

      // A tail of two or three characters is punctuation, not content: hand the model the rest of
      // the file WITHOUT this line's tail so it writes the whole line, closing brackets included.
      suffixTail.length <= TRIVIAL_SUFFIX_CHARS ->
        Plan(Type.SINGLE_LINE_REDO_SUFFIX, prefix, dropFirstLine(suffix), LINE_BREAKS)

      !prefixEmpty ->
        Plan(Type.SINGLE_LINE_MIDDLE, prefix, suffix, LINE_BREAKS)

      // Caret at the start of a line that already has text to the right: whatever the model writes
      // lands in front of someone else's code. Nothing good comes of asking.
      else -> Plan(Type.DO_NOT_PREDICT, prefix, suffix, emptyList())
    }
  }

  /** What we last showed as grey text, to recognise our own suggestion once it lands in the file. */
  data class Served(val path: String, val caretAfterInsert: Int, val text: String)

  /**
   * Was the previous suggestion accepted?
   *
   * Derived rather than subscribed: the platform's acceptance event lives behind an internal
   * listener bound to an editor's handler, while the fact itself is visible in the document — the
   * caret sits exactly where our text would have ended, and that text is right behind it. Pure,
   * so the rule that decides between a one-line and a multi-line request is testable.
   */
  fun wasJustAccepted(served: Served?, path: String, caretOffset: Int, textBeforeCaret: String): Boolean {
    val last = served ?: return false
    if (last.path != path || last.caretAfterInsert != caretOffset) return false
    return last.text.isNotEmpty() && textBeforeCaret.endsWith(last.text)
  }

  /** Keeps the last [lines] lines of the prefix — the caret end is what matters. */
  fun limitPrefix(text: String, lines: Int): String =
    text.split("\n").takeLast(lines).joinToString("\n")

  /** Keeps the first [lines] lines of the suffix. */
  fun limitSuffix(text: String, lines: Int): String =
    text.split("\n").take(lines).joinToString("\n")

  fun contextLines(isLocal: Boolean): Int = if (isLocal) CONTEXT_LINES_LOCAL else CONTEXT_LINES_CLOUD

  private fun dropFirstLine(suffix: String): String {
    val newline = suffix.indexOf('\n')
    return if (newline < 0) "" else suffix.substring(newline)
  }
}
