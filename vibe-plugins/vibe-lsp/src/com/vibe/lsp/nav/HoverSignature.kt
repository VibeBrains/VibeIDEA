// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.nav

/**
 * The declaration a hover answer starts with, cut to what a one-glance hint can hold.
 *
 * Servers put the signature first, in a fenced code block — `function encodeURIComponent(uriComponent: string):
 * string` from vtsls, `public function send(Message $message): void` from Intelephense — and what follows is
 * documentation, which a hint has no room for. An answer without a code block gives its first line of text.
 *
 * Pure: markup in, text out.
 */
object HoverSignature {
  /** Lines a hint keeps: an expanded TypeScript type runs to hundreds of lines, and a hint is read at a glance. */
  const val MAX_LINES = 6

  /** Characters a hint keeps, for the same reason: a single line of a generic signature can be thousands wide. */
  const val MAX_CHARS = 400

  private const val ELLIPSIS = "…"

  /** A fence opens with three or more backticks or tildes, optionally followed by the language. */
  private val FENCE = Regex("""^\s*(`{3,}|~{3,})""")

  /** Emphasis a first line of prose is often wrapped in (`**color**`, a backticked name); longest markers first. */
  private val WRAPPERS = listOf("**", "__", "`", "*", "_")

  /** The signature from the hover [markups] (each a markdown or plain-text part), or null when there is none. */
  fun of(markups: List<String>): String? {
    for (markup in markups) {
      codeBlock(markup)?.let { return cap(it) }
    }
    val line = markups.asSequence()
      .flatMap { it.lineSequence() }
      .map { it.trim() }
      .firstOrNull { it.isNotEmpty() && !FENCE.containsMatchIn(it) }
      ?: return null
    return cap(unwrap(line)).takeIf { it.isNotBlank() }
  }

  private fun codeBlock(markup: String): String? {
    val lines = markup.lines()
    val open = lines.indexOfFirst { FENCE.containsMatchIn(it) }
    if (open < 0) return null
    val marker = FENCE.find(lines[open])!!.groupValues[1]
    val body = lines.drop(open + 1).takeWhile { !closes(it, marker) }
    // Intelephense starts every block with `<?php`, so that its highlighter knows the language; it is not code.
    val code = body.dropWhile { it.isBlank() || it.trim() == "<?php" }.dropLastWhile { it.isBlank() }
    return code.takeIf { it.isNotEmpty() }?.joinToString("\n")
  }

  /** A closing fence: only the fence character, at least as many as opened the block. */
  private fun closes(line: String, marker: String): Boolean {
    val trimmed = line.trim()
    return trimmed.length >= marker.length && trimmed.all { it == marker[0] }
  }

  private fun unwrap(line: String): String {
    var text = line
    while (true) {
      val wrapper = WRAPPERS.firstOrNull { text.length > 2 * it.length && text.startsWith(it) && text.endsWith(it) } ?: return text
      text = text.substring(wrapper.length, text.length - wrapper.length).trim()
    }
  }

  private fun cap(text: String): String {
    val lines = text.lines()
    var kept = if (lines.size > MAX_LINES) lines.take(MAX_LINES).joinToString("\n") + "\n" + ELLIPSIS else text
    if (kept.length > MAX_CHARS) kept = kept.take(MAX_CHARS).trimEnd() + ELLIPSIS
    return kept
  }
}
