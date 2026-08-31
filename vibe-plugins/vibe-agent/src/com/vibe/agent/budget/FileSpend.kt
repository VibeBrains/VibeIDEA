// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.budget

/**
 * Which files the money went on.
 *
 * The ledger knows what a turn cost; the composer knows what travelled in it. Neither answers the
 * question people actually ask — «какой файл съел больше всего», — and that question is the one
 * that changes behaviour: a 400 KB generated bundle silently attached to every turn is invisible in
 * a per-role report and obvious here.
 *
 * The number is an ESTIMATE and is labelled as one everywhere it is shown. A request is billed as a
 * whole, and the split between its parts cannot be measured — only apportioned by size. Presenting
 * that as a measurement would be inventing precision; refusing to split at all would leave the
 * question unanswered, which is worse.
 */
object FileSpend {
  data class Attachment(val path: String, val chars: Int)

  /**
   * Splits the turn's tokens between its attachments in proportion to their size.
   *
   * The remainder goes to the largest attachment rather than being dropped: the parts must add up
   * to the whole, or the report will quietly under-report the total and nobody will notice.
   */
  fun attribute(tokens: Long, attachments: List<Attachment>): Map<String, Long> {
    if (tokens <= 0) return emptyMap()
    val sized = attachments.filter { it.chars > 0 }
    if (sized.isEmpty()) return emptyMap()
    val total = sized.sumOf { it.chars.toLong() }
    val byPath = LinkedHashMap<String, Long>()
    for (attachment in sized) {
      val share = tokens * attachment.chars / total
      byPath[attachment.path] = (byPath[attachment.path] ?: 0) + share
    }
    val remainder = tokens - byPath.values.sum()
    if (remainder > 0) {
      val biggest = sized.maxByOrNull { it.chars }!!.path
      byPath[biggest] = (byPath[biggest] ?: 0) + remainder
    }
    return byPath
  }

  data class Line(val path: String, val tokens: Long, val turns: Int)

  /** The heaviest files of the window, most expensive first. */
  fun top(entries: List<SpendLedger.Entry>, limit: Int = 10): List<Line> {
    val tokens = HashMap<String, Long>()
    val turns = HashMap<String, Int>()
    for (entry in entries) {
      for ((path, share) in entry.files) {
        tokens[path] = (tokens[path] ?: 0) + share
        turns[path] = (turns[path] ?: 0) + 1
      }
    }
    return tokens.entries
      .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
      .take(limit)
      .map { Line(it.key, it.value, turns[it.key] ?: 0) }
  }
}
