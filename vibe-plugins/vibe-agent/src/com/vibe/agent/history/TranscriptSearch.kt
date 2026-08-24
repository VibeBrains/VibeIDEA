// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.history

/**
 * Search over chat transcripts — the whole conversation, not just its opening line
 * (pure Kotlin port of VibeIDE `transcriptSearch`).
 *
 * Weights are deliberate: a transcript has no headings and no file names; what it has is who
 * said it and when. The opening message is the de-facto thread title, and the user's own words
 * rank above the model's answers — that is how a conversation is remembered.
 *
 * Pure: threads in, ranked matches out. No I/O, no model, no caching — the caller debounces.
 */
object TranscriptSearch {
  /** A message at or under this length is quoted whole; longer ones are windowed around the match. */
  const val SNIPPET_WINDOW = 240

  /** The opening message is the de-facto title: a hit there outranks the same words said later. */
  private const val WEIGHT_FIRST_MESSAGE = 12
  /** The user's own phrasing — how a conversation is remembered. */
  private const val WEIGHT_USER = 6
  /** The model's answer still matters: an error text or a file name often appears only there. */
  private const val WEIGHT_ASSISTANT = 2
  /** Synthetic notices and other machinery: searchable, but never the reason to rank. */
  private const val WEIGHT_OTHER = 1
  /** Extra credit when every query term shows up somewhere in the thread. */
  private const val ALL_TERMS_BONUS = 10
  /**
   * Saturation cap on occurrences of ONE term inside ONE message. Without it a single long
   * message repeating a word outranks a thread where the topic is discussed across turns.
   */
  private const val MAX_HITS_PER_TERM_PER_MESSAGE = 3

  private const val ELLIPSIS = "…"
  private val WHITESPACE = Regex("\\s+")

  /** The «best match» line of a thread: which message, who said it, and the text to show. */
  data class Quote(val messageIndex: Int, val role: Role, val snippet: String)

  data class Match(val threadId: String, val score: Int, val quote: Quote?)

  /** Returns only matching threads (no zero-score entries), keyed by thread id. */
  fun search(query: String, threads: List<ChatThread>): Map<String, Match> {
    val terms = query.trim().lowercase().split(WHITESPACE).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return emptyMap()
    val result = LinkedHashMap<String, Match>()
    for (thread in threads) {
      val match = scoreThread(thread, terms) ?: continue
      result[thread.id] = match
    }
    return result
  }

  private fun scoreThread(thread: ChatThread, terms: List<String>): Match? {
    val titleIndex = thread.messages.indexOfFirst { it.role == Role.USER }
    var total = 0
    val termMatched = BooleanArray(terms.size)
    // Best NON-FIRST message, kept for the quote: the first message is the title the row
    // already shows, so quoting it again would say nothing new.
    var bestIndex = -1
    var bestScore = 0
    var bestTermIndex = -1

    for ((index, message) in thread.messages.withIndex()) {
      val lower = message.text.lowercase()
      if (lower.isEmpty()) continue
      val weight = weightOf(message.role, index == titleIndex)

      var messageScore = 0
      // The term contributing most to this message — the snippet window centres on it.
      var topTermIndex = -1
      var topSaturated = 0
      for ((termIndex, term) in terms.withIndex()) {
        val found = countOccurrences(lower, term)
        if (found == 0) continue
        termMatched[termIndex] = true
        val saturated = minOf(found, MAX_HITS_PER_TERM_PER_MESSAGE)
        messageScore += saturated * weight
        if (saturated > topSaturated) {
          topSaturated = saturated
          topTermIndex = termIndex
        }
      }
      if (messageScore == 0) continue
      total += messageScore
      if (index != titleIndex && messageScore > bestScore) {
        bestScore = messageScore
        bestIndex = index
        bestTermIndex = topTermIndex
      }
    }

    if (total == 0) return null
    if (terms.size >= 2 && termMatched.all { it }) total += ALL_TERMS_BONUS

    val quote = if (bestIndex == -1) null else {
      val best = thread.messages[bestIndex]
      Quote(bestIndex, best.role, snippet(best.text, terms[bestTermIndex]))
    }
    return Match(thread.id, total, quote)
  }

  /** The title weight belongs to the thread title (first USER message) — a trim marker at the head must not inherit it. */
  private fun weightOf(role: Role, isTitle: Boolean): Int = when {
    role == Role.OTHER -> WEIGHT_OTHER
    isTitle -> WEIGHT_FIRST_MESSAGE
    role == Role.USER -> WEIGHT_USER
    else -> WEIGHT_ASSISTANT
  }

  /** Counts non-overlapping occurrences of `needle` in already-lowercased `haystack`. */
  private fun countOccurrences(haystack: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var from = 0
    while (true) {
      val at = haystack.indexOf(needle, from)
      if (at < 0) return count
      count++
      from = at + needle.length
    }
  }

  /**
   * Single-line snippet: the whole message when it fits [SNIPPET_WINDOW], otherwise a window of
   * that many chars centred on the first occurrence of `term`, with ellipses where cut.
   */
  private fun snippet(text: String, term: String): String {
    if (text.length <= SNIPPET_WINDOW) return singleLine(text)
    val at = text.lowercase().indexOf(term)
    val half = SNIPPET_WINDOW / 2
    val centre = if (at < 0) 0 else at + term.length / 2
    val start = (centre - half).coerceIn(0, text.length - SNIPPET_WINDOW)
    val end = start + SNIPPET_WINDOW
    val prefix = if (start > 0) ELLIPSIS else ""
    val suffix = if (end < text.length) ELLIPSIS else ""
    return prefix + singleLine(text.substring(start, end)) + suffix
  }

  /** Collapses whitespace and newlines to single spaces — the row shows one line. */
  private fun singleLine(text: String): String = text.trim().replace(WHITESPACE, " ")
}
