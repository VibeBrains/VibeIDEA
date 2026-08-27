// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
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

  fun parse(text: String): List<MessageSegment> {
    if (!text.contains(FENCE)) return listOf(MessageSegment.Prose(text))
    val out = ArrayList<MessageSegment>()
    val lines = text.split("\n")
    val prose = StringBuilder()
    var i = 0
    fun flushProse() {
      if (prose.isNotEmpty()) {
        // Trim only the trailing newline we accumulate between segments, keep inner blank lines.
        out.add(MessageSegment.Prose(prose.toString().trimEnd('\n')))
        prose.setLength(0)
      }
    }
    while (i < lines.size) {
      val line = lines[i]
      val trimmed = line.trimStart()
      if (trimmed.startsWith(FENCE)) {
        flushProse()
        val lang = trimmed.removePrefix(FENCE).trim().takeIf { it.isNotEmpty() }
        val code = StringBuilder()
        i++
        var closed = false
        while (i < lines.size) {
          if (lines[i].trimStart().startsWith(FENCE)) { closed = true; i++; break }
          code.append(lines[i]).append('\n')
          i++
        }
        out.add(MessageSegment.Code(lang, code.toString().removeSuffix("\n")))
        if (!closed) break // unterminated fence: everything consumed as code
      } else {
        prose.append(line).append('\n')
        i++
      }
    }
    flushProse()
    // Drop empty prose that can appear between adjacent fences.
    return out.filterNot { it is MessageSegment.Prose && it.text.isBlank() }.ifEmpty { listOf(MessageSegment.Prose(text)) }
  }

  /** True when [text] has at least one fenced code block worth special rendering. */
  fun hasCode(text: String): Boolean = parse(text).any { it is MessageSegment.Code }
}
