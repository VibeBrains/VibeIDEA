// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

/**
 * Splits an agent message into prose and fenced code blocks so a coding agent's
 * answer renders code monospace with its own copy button. Pure and unit-tested;
 * the Swing rendering lives in [AgentMessage].
 *
 * A fence is a line whose trimmed form starts with ``` (optionally followed by a
 * language tag). An unterminated fence treats the remainder as code — a partial
 * stream never loses text.
 */
sealed interface MessageSegment {
  data class Prose(val text: String) : MessageSegment
  data class Code(val lang: String?, val code: String) : MessageSegment
}

object MessageSegments {
  private const val FENCE = "```"
  // Opening fence: any leading indent (agents nest fences in list items by 4+ spaces), a run of ≥3 backticks, then an optional info string (no backticks).
  private val OPEN = Regex("^([ \\t]*)(`{3,})[ \\t]*([^`]*?)[ \\t]*$")
  // Closing fence: any leading indent, a run of ≥3 backticks, nothing but whitespace after.
  private val CLOSE = Regex("^[ \\t]*(`{3,})[ \\t]*$")

  fun parse(text: String): List<MessageSegment> {
    if (!text.contains(FENCE)) return listOf(MessageSegment.Prose(text))
    val out = ArrayList<MessageSegment>()
    // Normalize CRLF/CR so no stray \r leaks into rendered code or prose.
    val lines = text.split(Regex("\\r\\n|\\r|\\n"))
    val prose = StringBuilder()
    var i = 0
    fun flushProse() {
      if (prose.isNotEmpty()) {
        out.add(MessageSegment.Prose(prose.toString().trimEnd('\n')))
        prose.setLength(0)
      }
    }
    while (i < lines.size) {
      val open = OPEN.matchEntire(lines[i])
      if (open != null) {
        flushProse()
        val fenceLen = open.groupValues[2].length // close must be ≥ this run → outer ```` not closed by inner ```
        val lang = open.groupValues[3].trim().substringBefore(' ').substringBefore('\t').takeIf { it.isNotEmpty() }
        val code = StringBuilder()
        val indent = open.groupValues[1].length
        i++
        var closed = false
        while (i < lines.size) {
          val close = CLOSE.matchEntire(lines[i])
          if (close != null && close.groupValues[1].length >= fenceLen) { closed = true; i++; break }
          code.append(lines[i].removeIndent(indent)).append('\n')
          i++
        }
        out.add(MessageSegment.Code(lang, code.toString().removeSuffix("\n")))
        if (!closed) break // unterminated fence: everything consumed as code
      } else {
        prose.append(lines[i]).append('\n')
        i++
      }
    }
    flushProse()
    // Drop empty prose AND empty code (a lone/trailing fence must not render an empty box).
    return out.filterNot {
      (it is MessageSegment.Prose && it.text.isBlank()) || (it is MessageSegment.Code && it.code.isBlank())
    }.ifEmpty { listOf(MessageSegment.Prose(text)) }
  }

  /** Drops up to [n] leading blanks: the code of a fence nested in a list item is indented with the fence */
  private fun String.removeIndent(n: Int): String {
    var k = 0
    while (k < n && k < length && (this[k] == ' ' || this[k] == '\t')) k++
    return substring(k)
  }

  /** True when [text] has at least one fenced code block worth special rendering. */
  fun hasCode(text: String): Boolean = parse(text).any { it is MessageSegment.Code }
}
