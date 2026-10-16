// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.lsp.selection

/**
 * Where the sentence around an offset starts and ends, for the fourth click of a mouse selection
 * (word, line, then sentence).
 *
 * A sentence runs from the end of the previous sentence to the next one, where a sentence ends at `.`, `!` or `?`
 * followed by whitespace or by the end of its container. A dot inside `obj.method` or `0.5` ends nothing.
 *
 * The container is the widest text the sentence may not leave:
 * the content of a quoted string, of a line comment or of a block comment when the offset is inside one,
 * otherwise the line without its indentation and its leading comment marks.
 * A container never spans lines: a multi-line string or comment is cut at its line.
 */
object SentenceBounds {
  private const val TERMINATORS = ".!?"
  private const val QUOTES = "'\"`"

  /** A half-open range `[start, end)` of the text. */
  data class Span(val start: Int, val end: Int)

  /** The trimmed sentence around [offset], or null when there is nothing but whitespace there. */
  fun find(text: CharSequence, offset: Int): Span? {
    val at = offset.coerceIn(0, text.length)
    val lineStart = lineStart(text, at)
    val lineEnd = lineEnd(text, at)
    val container = containerOf(text, lineStart, lineEnd, at)
    return sentenceIn(text, container, at)
  }

  private fun containerOf(text: CharSequence, lineStart: Int, lineEnd: Int, at: Int): Span {
    var i = lineStart
    while (i < lineEnd) {
      val c = text[i]
      val segment = when {
        c in QUOTES && opensQuote(text, lineStart, i) -> quotedSegment(text, i, lineEnd)
        c == '/' && i + 1 < lineEnd && text[i + 1] == '/' -> Span(i + 2, lineEnd)
        c == '/' && i + 1 < lineEnd && text[i + 1] == '*' -> blockSegment(text, i, lineEnd)
        else -> null
      }
      if (segment == null) {
        i++
        continue
      }
      if (at in segment.start..segment.end) return segment
      i = maxOf(segment.end + 1, i + 1)
    }
    return lineBody(text, lineStart, lineEnd)
  }

  /** A quote opens a string only after a non-word character: the apostrophe of "don't" opens nothing. */
  private fun opensQuote(text: CharSequence, lineStart: Int, i: Int): Boolean =
    i == lineStart || !text[i - 1].isLetterOrDigit() && text[i - 1] != '_'

  /** The content of the string opened at [open], or null when the line holds no closing quote (then it is prose). */
  private fun quotedSegment(text: CharSequence, open: Int, lineEnd: Int): Span? {
    val quote = text[open]
    var i = open + 1
    while (i < lineEnd) {
      when (text[i]) {
        '\\' -> i++
        quote -> return Span(open + 1, i)
      }
      i++
    }
    return null
  }

  private fun blockSegment(text: CharSequence, open: Int, lineEnd: Int): Span {
    var i = open + 2
    while (i + 1 < lineEnd) {
      if (text[i] == '*' && text[i + 1] == '/') return Span(open + 2, i)
      i++
    }
    return Span(open + 2, lineEnd)
  }

  /** The line without indentation and without the leading `*`, `//` and `#` of a comment line. */
  private fun lineBody(text: CharSequence, lineStart: Int, lineEnd: Int): Span {
    var i = lineStart
    while (i < lineEnd && text[i].isWhitespace()) i++
    while (i < lineEnd && (text[i] == '*' || text[i] == '/' || text[i] == '#')) i++
    return Span(i, lineEnd)
  }

  private fun sentenceIn(text: CharSequence, container: Span, at: Int): Span? {
    val p = at.coerceIn(container.start, container.end)
    var start = container.start
    var end = container.end
    var i = container.start
    while (i < container.end) {
      if (isTerminator(text, i, container.end)) {
        val after = i + 1
        if (after < p) {
          start = after
        }
        else {
          end = after
          break
        }
      }
      i++
    }
    while (start < end && text[start].isWhitespace()) start++
    while (end > start && text[end - 1].isWhitespace()) end--
    return if (start < end) Span(start, end) else null
  }

  private fun isTerminator(text: CharSequence, i: Int, containerEnd: Int): Boolean =
    text[i] in TERMINATORS && (i + 1 >= containerEnd || text[i + 1].isWhitespace())

  private fun lineStart(text: CharSequence, at: Int): Int {
    var i = at
    while (i > 0 && text[i - 1] != '\n') i--
    return i
  }

  private fun lineEnd(text: CharSequence, at: Int): Int {
    var i = at
    while (i < text.length && text[i] != '\n') i++
    return i
  }
}
