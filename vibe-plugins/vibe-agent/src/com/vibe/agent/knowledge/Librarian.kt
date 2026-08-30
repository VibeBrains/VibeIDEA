// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.knowledge

/**
 * What the project already wrote down about this, handed to the agent before it starts.
 *
 * Every project accumulates hard-won notes — «этот гейт нельзя чинить так», «здесь мы уже
 * пробовали и вот почему не вышло» — and an agent that does not read them re-derives them, badly,
 * at full price. Nobody remembers to paste the right note at the right moment, so the librarian
 * does it: it matches the request against the knowledge INDEX and names the two or three entries
 * worth opening.
 *
 * Paths, not contents. A note is often a page long, and inlining three of them costs more context
 * than the task itself; the agent reads what it decides it needs, which is also how a person uses
 * an index.
 */
object Librarian {
  /** One row of `docs/…/knowledge/README.md`: a link and the line describing it. */
  data class Entry(val path: String, val description: String)

  data class Hit(val entry: Entry, val score: Int)

  /** Below this the match is a coincidence of common words, and naming it is noise. */
  const val MIN_SCORE = 2
  const val MAX_HITS = 3

  /** Words shorter than this carry no topic (и, в, on, at) and would match everything. */
  private const val MIN_WORD_LENGTH = 4

  private val LINK = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")

  /**
   * Parses a markdown index: any line containing a link is an entry, and the rest of the line is
   * its description. Table pipes and list bullets are stripped, because both shapes are used in
   * real indexes and demanding one of them would make the librarian silently blind.
   */
  fun parseIndex(markdown: String): List<Entry> = markdown.lines().mapNotNull { line ->
    val match = LINK.find(line) ?: return@mapNotNull null
    val path = match.groupValues[2].trim()
    if (path.startsWith("http")) return@mapNotNull null
    val description = line.replace(LINK, match.groupValues[1])
      .trim().trim('|', '-', '*', ' ').replace('|', ' ').trim()
    Entry(path, description)
  }

  /**
   * Scores entries by how many meaningful words of the request appear in their description.
   *
   * Crude on purpose: a proper ranker needs an index, an index needs maintenance, and the goal
   * here is «не пропустить очевидное», not search. Ties keep index order, so the result does not
   * shuffle between identical turns.
   */
  fun find(entries: List<Entry>, request: String, minScore: Int = MIN_SCORE, maxHits: Int = MAX_HITS): List<Hit> {
    val words = wordsOf(request)
    if (words.isEmpty()) return emptyList()
    return entries.map { entry ->
      val haystack = (entry.description + " " + entry.path).lowercase()
      Hit(entry, words.count { word -> haystack.contains(word) })
    }.filter { it.score >= minScore }
      .sortedByDescending { it.score }
      .take(maxHits)
  }

  fun wordsOf(text: String): Set<String> =
    text.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
      .filter { it.length >= MIN_WORD_LENGTH }
      .toSet()

  /** The prompt block: paths and descriptions, with an explicit instruction to read before acting. */
  fun promptBlock(hits: List<Hit>, header: String): String {
    if (hits.isEmpty()) return ""
    return buildString {
      appendLine(header)
      for (hit in hits) appendLine("- ${hit.entry.path} — ${hit.entry.description}")
    }.trimEnd()
  }
}
