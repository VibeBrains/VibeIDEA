// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

/**
 * A manually typed `@`-reference found in the composer text (VibeIDE mention syntax).
 * [raw] is the exact source substring (including `@`), [range] its position: start of `@`,
 * end exclusive. Pure model: no IDE types, resolution lives in [MentionResolver].
 */
sealed interface MentionToken {
  val raw: String
  val range: IntRange

  data class Path(override val raw: String, override val range: IntRange, val path: String) : MentionToken
  data class Folder(override val raw: String, override val range: IntRange, val path: String) : MentionToken
  data class Symbol(override val raw: String, override val range: IntRange, val name: String) : MentionToken
  data class Selection(override val raw: String, override val range: IntRange) : MentionToken
  data class Workspace(override val raw: String, override val range: IntRange) : MentionToken
  data class Recent(override val raw: String, override val range: IntRange) : MentionToken
  data class Agent(override val raw: String, override val range: IntRange) : MentionToken
}

/**
 * Parser for `@`-mentions typed by hand. Recognised forms:
 * `@"path with spaces"`, `@path/name`, `@selection`, `@workspace`, `@recent`, `@agent`,
 * `@sym:Name` / `@symbol:Name`, `@folder:path`.
 *
 * A `@` starts a token only at text start or after whitespace / one of `( [ { " '`,
 * so e-mail addresses are left alone. A lone `@` or `@` followed by whitespace is not a token.
 */
object MentionSyntax {
  private const val AT = '@'
  private const val QUOTE = '"'
  private const val KEYWORD_SELECTION = "selection"
  private const val KEYWORD_WORKSPACE = "workspace"
  private const val KEYWORD_RECENT = "recent"
  private const val KEYWORD_AGENT = "agent"
  private const val PREFIX_SYM = "sym:"
  private const val PREFIX_SYMBOL = "symbol:"
  private const val PREFIX_FOLDER = "folder:"

  /** Characters after which a `@` may open a token (besides whitespace and text start). */
  private const val OPENERS = "([{\"'"

  /**
   * Punctuation stripped from the tail of an unquoted value (`@a.kt,` -> `a.kt`). Closing quotes are
   * included because `"` and `'` are openers: `'@c.kt'` must yield `c.kt`, not `c.kt'`.
   */
  private const val TRAILING_PUNCTUATION = ".,;:!?)]}\"'"

  /** Characters that may directly follow a keyword without gluing to it (`@agent.` is still `agent`). */
  private const val KEYWORD_TERMINATORS = TRAILING_PUNCTUATION

  fun parse(text: String): List<MentionToken> {
    val tokens = ArrayList<MentionToken>()
    var i = 0
    while (i < text.length) {
      if (text[i] != AT || !canOpenAt(text, i)) {
        i++
        continue
      }
      val token = readToken(text, i)
      if (token == null) {
        i++
      }
      else {
        tokens.add(token)
        i = token.range.last + 1
      }
    }
    return tokens
  }

  private fun canOpenAt(text: String, at: Int): Boolean {
    if (at == 0) return true
    val prev = text[at - 1]
    return prev.isWhitespace() || prev in OPENERS
  }

  /** Reads the token starting at [at] (which holds `@`); null when nothing valid follows. */
  private fun readToken(text: String, at: Int): MentionToken? {
    val valueStart = at + 1
    if (valueStart >= text.length || text[valueStart].isWhitespace()) return null

    if (text[valueStart] == QUOTE) {
      val closing = text.indexOf(QUOTE, valueStart + 1)
      if (closing < 0) return null
      val path = text.substring(valueStart + 1, closing)
      if (path.isBlank()) return null
      val end = closing + 1
      return MentionToken.Path(text.substring(at, end), at until end, path)
    }

    var end = valueStart
    while (end < text.length && !text[end].isWhitespace()) end++
    val word = text.substring(valueStart, end)

    keyword(text, at, word, end)?.let { return it }

    prefixed(word, PREFIX_SYM)?.let { return symbol(text, at, valueStart, PREFIX_SYM, it) }
    prefixed(word, PREFIX_SYMBOL)?.let { return symbol(text, at, valueStart, PREFIX_SYMBOL, it) }
    prefixed(word, PREFIX_FOLDER)?.let { value ->
      val trimmed = value.trimEnd { it in TRAILING_PUNCTUATION }
      if (trimmed.isEmpty()) return null
      val tokenEnd = valueStart + PREFIX_FOLDER.length + trimmed.length
      return MentionToken.Folder(text.substring(at, tokenEnd), at until tokenEnd, trimmed)
    }

    val trimmed = word.trimEnd { it in TRAILING_PUNCTUATION }
    if (trimmed.isEmpty()) return null
    val tokenEnd = valueStart + trimmed.length
    return MentionToken.Path(text.substring(at, tokenEnd), at until tokenEnd, trimmed)
  }

  /** Exact lowercase keyword; whatever follows must be trailing punctuation (`@agent.`), not a name tail (`@agent.md`). */
  private fun keyword(text: String, at: Int, word: String, wordEnd: Int): MentionToken? {
    val keyword = listOf(KEYWORD_SELECTION, KEYWORD_WORKSPACE, KEYWORD_RECENT, KEYWORD_AGENT)
      .firstOrNull { word.startsWith(it) && word.substring(it.length).all { c -> c in KEYWORD_TERMINATORS } }
      ?: return null
    val end = wordEnd - (word.length - keyword.length)
    val raw = text.substring(at, end)
    val range = at until end
    return when (keyword) {
      KEYWORD_SELECTION -> MentionToken.Selection(raw, range)
      KEYWORD_WORKSPACE -> MentionToken.Workspace(raw, range)
      KEYWORD_RECENT -> MentionToken.Recent(raw, range)
      else -> MentionToken.Agent(raw, range)
    }
  }

  /**
   * Value after a case-insensitive [prefix] (possibly empty), or null when the word does not start with it.
   * A prefix with an empty value (`@sym:`) is a prefix token with nothing to resolve, so the caller yields no token.
   */
  private fun prefixed(word: String, prefix: String): String? =
    if (word.startsWith(prefix, ignoreCase = true)) word.substring(prefix.length) else null

  private fun symbol(text: String, at: Int, valueStart: Int, prefix: String, value: String): MentionToken? {
    val trimmed = value.trimEnd { it in TRAILING_PUNCTUATION }
    if (trimmed.isEmpty()) return null
    val tokenEnd = valueStart + prefix.length + trimmed.length
    return MentionToken.Symbol(text.substring(at, tokenEnd), at until tokenEnd, trimmed)
  }
}
