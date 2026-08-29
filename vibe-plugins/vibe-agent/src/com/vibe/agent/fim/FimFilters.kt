// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.fim

/**
 * Cleans what the model sent back before it is shown as grey text.
 *
 * A FIM model asked for code sometimes answers like a chat: an explanation, a comment in its
 * training language, a apology. Inline completion has no room for any of it — the text is inserted
 * into the file on Tab. So lines that are clearly not code are dropped rather than shown.
 *
 * The hard part is what NOT to drop: a Russian comment, a Chinese string literal and an emoji in a
 * test name are all legitimate code. Hence the rule works on the RATIO of non-ASCII to the line
 * plus the absence of any code punctuation, not on the mere presence of a non-Latin letter.
 */
object FimFilters {
  /** Above this share of non-ASCII, and with no code punctuation at all, a line reads as prose. */
  private const val NON_ASCII_PROSE_RATIO = 0.5

  private val CODE_INDICATORS = Regex("[{}()\\[\\];=+\\-*/<>]")

  /** CJK inside a trailing line comment: the one shape that is nearly always training noise. */
  private val CJK_LINE_COMMENT = Regex("//.*[\\u4e00-\\u9fff\\u3040-\\u309f\\u30a0-\\u30ff\\uac00-\\ud7af]")
  private val CJK_BLOCK_COMMENT = Regex("/\\*.*[\\u4e00-\\u9fff\\u3040-\\u309f\\u30a0-\\u30ff\\uac00-\\ud7af].*\\*/")

  fun clean(text: String): String {
    if (text.isEmpty()) return text
    val kept = ArrayList<String>()
    for (line in text.split("\n")) {
      if (CJK_LINE_COMMENT.containsMatchIn(line) || CJK_BLOCK_COMMENT.containsMatchIn(line)) {
        // Keep the code, drop the comment — the completion is usually right, the comment is noise.
        val codeOnly = line.replace(Regex("//.*$"), "").replace(Regex("/\\*.*?\\*/"), "").trimEnd()
        if (codeOnly.isNotBlank()) kept.add(codeOnly)
        continue
      }
      if (isProse(line)) continue
      kept.add(line)
    }
    return kept.joinToString("\n")
  }

  /** A line of prose: mostly non-ASCII and without a single piece of code punctuation. */
  fun isProse(line: String): Boolean {
    if (line.isBlank()) return false
    val nonAscii = line.count { it.code > 127 }
    val ratio = nonAscii.toDouble() / line.length
    return ratio > NON_ASCII_PROSE_RATIO && !CODE_INDICATORS.containsMatchIn(line)
  }

  /**
   * Trims the completion the way it will be inserted: at most one leading and one trailing space
   * survives, everything else goes. A stray blank line after the suggestion moves the caret.
   */
  fun trimEdges(text: String): String {
    val leading = if (text.startsWith(" ")) " " else ""
    val trailing = if (text.endsWith(" ")) " " else ""
    val core = text.trim()
    return if (core.isEmpty()) "" else leading + core + trailing
  }
}
