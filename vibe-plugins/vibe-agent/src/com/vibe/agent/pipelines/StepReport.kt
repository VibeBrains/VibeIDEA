// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

/**
 * What a step says about itself when it ends, in fields rather than in prose.
 *
 * Until 17.09.2026 the next step got the tail of the answer — two thousand characters of whatever the agent chose to
 * say last — and the acceptance gate got the diff. Neither says the one thing both need: did the step do the work, or
 * did it stop because something was missing. A named status separates «could not» from «the call failed», and blockers
 * give the gate something to judge that is not a guess about the diff (Autopilot's return contract, 17.09.2026).
 *
 * Read leniently on purpose: the report is written by a model. A missing block is absent, not an error; an unknown
 * status is [Status.UNKNOWN], and prose without a single field parses as nothing at all, so the old tail keeps working.
 *
 * Pure: the answer's text in, the report out.
 */
data class StepReport(
  val status: Status,
  val files: List<String> = emptyList(),
  val tests: String? = null,
  val interfaces: List<String> = emptyList(),
  val requirements: List<String> = emptyList(),
  val concerns: List<String> = emptyList(),
  val blockers: List<String> = emptyList(),
) {
  enum class Status {
    /** The work of the step is done. */
    DONE,

    /** Done, and the step has something to say about it — kept, never a reason to stop the run. */
    DONE_WITH_CONCERNS,

    /** Not done, and the step says why in [blockers]. */
    BLOCKED,

    /** The step wrote a report but named no status we know. */
    UNKNOWN;

    companion object {
      fun of(word: String): Status = when (word.trim().uppercase().replace(' ', '_')) {
        "DONE" -> DONE
        "DONE_WITH_CONCERNS" -> DONE_WITH_CONCERNS
        "BLOCKED" -> BLOCKED
        else -> UNKNOWN
      }
    }
  }

  /** What the next step is told: the fields that carry over, in the order they are read. */
  fun summary(): String = buildList {
    add("STATUS: " + status.name)
    tests?.let { add("TESTS: $it") }
    if (files.isNotEmpty()) add("FILES: " + files.joinToString(", "))
    if (interfaces.isNotEmpty()) add("INTERFACES: " + interfaces.joinToString("; "))
    if (requirements.isNotEmpty()) add("REQUIREMENTS: " + requirements.joinToString("; "))
    if (blockers.isNotEmpty()) add("BLOCKERS: " + blockers.joinToString("; "))
    if (concerns.isNotEmpty()) add("CONCERNS: " + concerns.joinToString("; "))
  }.joinToString("\n")

  companion object {
    /** The blocks a step may write, in the order the contract states them. */
    val FIELDS: List<String> = listOf("STATUS", "FILES", "TESTS", "INTERFACES", "REQUIREMENTS", "CONCERNS", "BLOCKERS")

    private val HEADER = Regex("^\\s*(?:[-*]\\s*)?(${FIELDS.joinToString("|")})\\s*:\\s*(.*)$", RegexOption.IGNORE_CASE)

    /** Bold marks around a field name are the model's formatting, not part of the word. */
    private val BOLD = Regex("\\*\\*")

    /** Nothing to take from a line that only marks a list item. */
    private val BULLET = Regex("^\\s*(?:[-*•]|\\d+[.)])\\s*")

    /**
     * The report at the end of [answer], or null when the step wrote none.
     *
     * The LAST occurrence of each field wins: a step that shows the contract to itself mid-answer and then fills it in
     * would otherwise be read by its own example. A report needs a status — fields without one are prose that happens
     * to contain a colon.
     */
    fun parse(answer: String): StepReport? {
      val blocks = LinkedHashMap<String, MutableList<String>>()
      var current: String? = null
      for (raw in answer.lines()) {
        val header = HEADER.matchEntire(BOLD.replace(raw, ""))
        if (header != null) {
          val field = header.groupValues[1].uppercase()
          current = field
          blocks[field] = mutableListOf()
          header.groupValues[2].trim().takeIf { it.isNotEmpty() }?.let { blocks[field]!!.add(it) }
          continue
        }
        val line = BOLD.replace(raw, "").trim()
        if (line.isEmpty()) { current = null; continue }
        // A line under a field continues it only while it looks like an item of that field's list.
        val item = BULLET.find(line)?.let { line.substring(it.value.length).trim() } ?: continue
        if (current != null && item.isNotEmpty()) blocks[current]!!.add(item)
      }
      val status = blocks["STATUS"]?.firstOrNull() ?: return null
      fun list(field: String): List<String> = blocks[field].orEmpty()
        .flatMap { value -> if (value.contains(',') && field == "FILES") value.split(',') else listOf(value) }
        .map { it.trim().trim('`') }.filter { it.isNotEmpty() && !it.equals("нет", true) && !it.equals("none", true) }
      return StepReport(
        status = Status.of(status),
        files = list("FILES"),
        tests = blocks["TESTS"].orEmpty().joinToString(" ").trim().ifEmpty { null },
        interfaces = list("INTERFACES"),
        requirements = list("REQUIREMENTS"),
        concerns = list("CONCERNS"),
        blockers = list("BLOCKERS"),
      )
    }
  }
}
