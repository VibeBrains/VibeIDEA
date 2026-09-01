// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

/**
 * The journal answered as a question about people rather than about lines.
 *
 * Attribution that cannot be read is attribution that does not exist: `actor` on every record buys
 * nothing while the only view is a wall of JSON. What an investigation actually asks first is «что
 * тут делал агент, а что я сам» — and that is two numbers and a list of actions, not three hundred
 * lines to scroll.
 *
 * Pure: parses nothing it does not need, and takes lines as strings so it can be tested without a
 * file, a project or an IDE.
 */
object AuditSummary {
  data class Row(val actor: String, val role: String?, val records: Int, val failures: Int, val actions: List<String>)

  data class Report(val total: Int, val unattributed: Int, val rows: List<Row>)

  /**
   * Groups by actor kind plus role: «agent (reviewer)» and «agent (developer)» are different
   * answers to «кто это сделал», and merging them hides the one thing a pipeline is for.
   */
  fun of(lines: List<String>): Report {
    data class Acc(var records: Int = 0, var failures: Int = 0, val actions: LinkedHashMap<String, Int> = LinkedHashMap())

    val groups = LinkedHashMap<Pair<String, String?>, Acc>()
    var unattributed = 0
    var total = 0
    for (line in lines) {
      if (line.isBlank()) continue
      total++
      val kind = field(line, "kind")
      if (kind == null) {
        // Records written before `actor` existed. Counted and named rather than dropped: an old
        // journal is not an empty one, and silently ignoring it would understate what happened.
        unattributed++
        continue
      }
      val acc = groups.getOrPut(kind to field(line, "role")) { Acc() }
      acc.records++
      if (field(line, "ok") == "false" || line.contains("\"ok\":false")) acc.failures++
      field(line, "action")?.let { acc.actions[it] = (acc.actions[it] ?: 0) + 1 }
    }
    // Busiest first: a report sorted by name hides the answer, the same rule as the spend report.
    val rows = groups.entries
      .map { (key, acc) ->
        Row(key.first, key.second, acc.records, acc.failures,
            acc.actions.entries.sortedByDescending { it.value }.map { "${it.key} × ${it.value}" })
      }
      .sortedByDescending { it.records }
    return Report(total, unattributed, rows)
  }

  /**
   * A string field of a flat JSONL line, read by scanning rather than parsing.
   *
   * The journal is append-only text that may contain records from an older format; a parser would
   * refuse a line the reader could still learn something from, and a report that dies on one bad
   * line is a report nobody trusts.
   */
  private fun field(line: String, name: String): String? {
    val marker = "\"$name\":"
    val at = line.indexOf(marker)
    if (at < 0) return null
    var i = at + marker.length
    while (i < line.length && line[i] == ' ') i++
    if (i >= line.length) return null
    return if (line[i] == '"') {
      val end = line.indexOf('"', i + 1)
      if (end < 0) null else line.substring(i + 1, end)
    }
    else {
      val end = line.indexOfFirst(i) { it == ',' || it == '}' }
      if (end < 0) null else line.substring(i, end).trim()
    }
  }

  private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
    for (i in from until length) if (predicate(this[i])) return i
    return -1
  }
}
