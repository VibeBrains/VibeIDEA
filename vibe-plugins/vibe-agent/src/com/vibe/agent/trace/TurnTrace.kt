// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.trace

/**
 * What the turn actually did, step by step — the answer to «почему оно пошло не туда».
 *
 * The feed shows the story the agent tells about itself; the trace shows what happened. The two
 * differ exactly where it matters: a tool that failed and was retried three times, a gate that
 * bounced the turn back, a wait on a rate limit, a file read twice for no reason. None of that is
 * visible in prose, and without it the honest answer to «почему так долго и почему так дорого» is
 * a guess.
 *
 * Pure and bounded: a trace that grows with the turn would cost the memory the turn is trying to
 * save, and a trace nobody can test is a story of its own.
 */
object TurnTrace {
  enum class Kind { TOOL, GATE, RETRY, LOOP, PLAN, HOOK, ERROR, NOTE }

  data class Event(
    val atMs: Long,
    val kind: Kind,
    val name: String,
    /** Milliseconds the step took, when it is known — a duration nobody measured is not zero. */
    val durationMs: Long? = null,
    val ok: Boolean = true,
    val detail: String? = null,
  )

  data class Summary(val kind: Kind, val count: Int, val failures: Int, val totalMs: Long)

  /** Bounded recorder for one turn. */
  class Recorder(private val limit: Int = MAX_EVENTS) {
    private val events = ArrayDeque<Event>()

    @Synchronized
    fun add(event: Event) {
      events.addLast(event)
      // The oldest go first: the end of a turn explains its outcome, the beginning rarely does.
      while (events.size > limit) events.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<Event> = events.toList()

    @Synchronized
    fun clear() = events.clear()

    @Synchronized
    fun size(): Int = events.size
  }

  fun summarise(events: List<Event>): List<Summary> =
    events.groupBy { it.kind }.map { (kind, group) ->
      Summary(kind, group.size, group.count { !it.ok }, group.sumOf { it.durationMs ?: 0 })
    }.sortedWith(compareByDescending<Summary> { it.totalMs }.thenByDescending { it.count })

  /** Steps that took the longest — the first place one looks when a turn was slow. */
  fun slowest(events: List<Event>, count: Int = 5): List<Event> =
    events.filter { it.durationMs != null }.sortedByDescending { it.durationMs }.take(count)

  /** Steps repeated with the same name: the cheap signal that work was done twice. */
  fun repeated(events: List<Event>, threshold: Int = 2): Map<String, Int> =
    events.filter { it.kind == Kind.TOOL }
      .groupingBy { it.name }.eachCount()
      .filterValues { it >= threshold }

  /**
   * Calls that are not identical but are about the same thing — «прочитал 1–50, потом 1–60».
   *
   * OBSERVATION, not a verdict. A semantic loop is real and expensive, but a detector that stops a
   * turn on it has to parse somebody else's tool arguments, and that parsing is exactly what
   * VibeIDE found too fragile to ship. So the trace shows the count and the person decides whether
   * they are looking at a loop or at a legitimate second pass; if the same pattern keeps showing up
   * in real traces, THEN a detector has a case to be built on.
   *
   * Numbers and bracketed parts are dropped from the name, because that is where ranges, offsets
   * and page numbers live — the parts that make two readings of one file look like two jobs.
   */
  fun nearRepeats(events: List<Event>, threshold: Int = 3): Map<String, Int> =
    events.filter { it.kind == Kind.TOOL }
      .groupingBy { normalizeName(it.name) }.eachCount()
      .filterValues { it >= threshold }

  internal fun normalizeName(name: String): String =
    name.replace(Regex("[\\(\\[][^)\\]]*[\\)\\]]"), "")
      .replace(Regex("\\d+"), "")
      .replace(Regex("\\s+"), " ")
      .trim()
      .trim('-', ':', ',')
      .trim()

  fun render(events: List<Event>, startedAtMs: Long, labels: Labels): String {
    if (events.isEmpty()) return labels.empty
    return buildString {
      appendLine(labels.header(events.size))
      for (event in events) {
        val offset = ((event.atMs - startedAtMs) / 1000.0).coerceAtLeast(0.0)
        val duration = event.durationMs?.let { " (${it} ${labels.ms})" } ?: ""
        val mark = if (event.ok) " " else labels.failureMark
        // Locale.ROOT: with a Russian locale the default format prints «0,0s», and a trace whose
        // numbers change shape with the interface language is a trace nobody can grep.
        appendLine(String.format(java.util.Locale.ROOT, "  %6.1fs %s%s %s%s",
                                 offset, mark, labels.kind(event.kind), event.name, duration))
        event.detail?.takeIf { it.isNotBlank() }?.let { appendLine("           ${it.take(DETAIL_LIMIT)}") }
      }
    }.trimEnd()
  }

  interface Labels {
    fun header(count: Int): String
    fun kind(kind: Kind): String
    val empty: String
    val ms: String
    val failureMark: String
  }

  const val MAX_EVENTS = 500
  private const val DETAIL_LIMIT = 200
}
