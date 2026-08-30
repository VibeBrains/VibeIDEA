// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.budget

/**
 * Where the tokens went: by role and by model, over a rolling window.
 *
 * The question this answers is the one people ask right after their first bill — «на что это
 * ушло?» — and it cannot be answered by a number in a status bar. A single total says the month
 * cost money; a split by role says the reviewer ran on the expensive model for no reason, which is
 * something one can actually act on.
 *
 * Pure and windowed: entries older than the window are dropped on every read, so the structure
 * cannot grow without bound and the answer never silently includes last month.
 */
object SpendLedger {
  /** One accounted step: tokens are always known, money only when the provider reports it. */
  data class Entry(
    val atMs: Long,
    /** Pipeline role, or null for an ordinary chat — they are different lines in the report. */
    val role: String?,
    val target: String,
    val tokens: Long,
    val costAmount: Double? = null,
    val costCurrency: String? = null,
  )

  data class Line(val name: String, val tokens: Long, val cost: Double, val currency: String?, val runs: Int)

  /** Bounded store; the cap is a backstop, the window is the real limit. */
  class Store(private val capacity: Int = 5_000) {
    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun add(entry: Entry) {
      entries.addLast(entry)
      while (entries.size > capacity) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()
  }

  fun within(entries: List<Entry>, nowMs: Long, windowMs: Long): List<Entry> =
    entries.filter { nowMs - it.atMs <= windowMs }

  fun tokensOf(entries: List<Entry>, role: String?): Long =
    entries.filter { it.role == role }.sumOf { it.tokens }

  /** Report lines, biggest spender first: a report sorted by name hides the answer. */
  fun byRole(entries: List<Entry>): List<Line> = group(entries) { it.role ?: CHAT }

  fun byTarget(entries: List<Entry>): List<Line> = group(entries) { it.target }

  private fun group(entries: List<Entry>, key: (Entry) -> String): List<Line> =
    entries.groupBy(key).map { (name, group) ->
      Line(
        name = name,
        tokens = group.sumOf { it.tokens },
        cost = group.sumOf { it.costAmount ?: 0.0 },
        // Mixing currencies into one number would be a lie; when they differ the report says so.
        currency = group.mapNotNull { it.costCurrency }.distinct().singleOrNull(),
        runs = group.size,
      )
    }.sortedWith(compareByDescending<Line> { it.tokens }.thenBy { it.name })

  const val CHAT = "chat"
  const val DAY_MS = 24 * 60 * 60 * 1000L
}
