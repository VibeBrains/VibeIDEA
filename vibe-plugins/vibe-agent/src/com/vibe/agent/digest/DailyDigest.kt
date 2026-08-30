// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.digest

import com.vibe.agent.budget.SpendLedger
import com.vibe.agent.runs.AgentRunLedger

/**
 * What the agents did over the last day, in one paragraph.
 *
 * It exists because of unattended work: pipelines, external HTTP calls, runs started from
 * somewhere else. Each reports into its own place and none of them is looked at, so what actually
 * happened yesterday is knowable only by reading three logs — which nobody does.
 *
 * The digest answers three questions and no more: сколько прогонов, что упало, сколько потрачено.
 * A digest that tries to summarise everything becomes a report, and a report is not read either.
 */
object DailyDigest {
  data class Stats(
    val runs: Int,
    val failed: Int,
    val orphaned: Int,
    val tokens: Long,
    val topRole: String?,
    val changedFiles: Int,
  ) {
    val isQuiet: Boolean get() = runs == 0 && tokens == 0L
  }

  fun collect(runs: List<AgentRunLedger.Run>, spend: List<SpendLedger.Entry>, sinceMs: Long): Stats {
    val recent = runs.filter { it.startedAtMs >= sinceMs }
    val byRole = SpendLedger.byRole(spend)
    return Stats(
      runs = recent.size,
      failed = recent.count { it.status == AgentRunLedger.Status.FAILED },
      // Orphans are counted apart from failures: a run whose window died is not a run that went
      // wrong, and merging them hides both.
      orphaned = recent.count { it.status == AgentRunLedger.Status.ORPHANED },
      tokens = spend.sumOf { it.tokens },
      topRole = byRole.firstOrNull()?.name,
      changedFiles = recent.sumOf { it.changedFiles },
    )
  }

  /** Every word comes from the caller's catalogue; this object only decides WHAT is worth saying. */
  fun render(stats: Stats, labels: Labels): String {
    if (stats.isQuiet) return labels.quiet
    return buildString {
      append(labels.runs(stats.runs, stats.changedFiles))
      if (stats.failed > 0 || stats.orphaned > 0) append(" ").append(labels.problems(stats.failed, stats.orphaned))
      if (stats.tokens > 0) append(" ").append(labels.spend(stats.tokens, stats.topRole))
    }
  }

  interface Labels {
    val quiet: String
    fun runs(count: Int, changedFiles: Int): String
    fun problems(failed: Int, orphaned: Int): String
    fun spend(tokens: Long, topRole: String?): String
  }

  /** Minutes since midnight for `HH:MM`, or null when the setting is not a time. */
  fun minutesOfDay(text: String?): Int? {
    val parts = text?.trim()?.split(':') ?: return null
    if (parts.size != 2) return null
    val hours = parts[0].toIntOrNull() ?: return null
    val minutes = parts[1].toIntOrNull() ?: return null
    if (hours !in 0..23 || minutes !in 0..59) return null
    return hours * 60 + minutes
  }

  /**
   * Is it time to send today's digest?
   *
   * [lastSentDay] makes it once per day even if the IDE is restarted five times. The grace window
   * makes a digest that was scheduled while the IDE was closed arrive when it is opened — but only
   * for a while: a summary of yesterday delivered at noon is noise, not information.
   */
  fun shouldSend(
    nowMinutes: Int,
    today: Long,
    scheduledMinutes: Int?,
    lastSentDay: Long,
    graceMinutes: Int = GRACE_MINUTES,
  ): Boolean {
    if (scheduledMinutes == null) return false
    if (lastSentDay >= today) return false
    return nowMinutes >= scheduledMinutes && nowMinutes - scheduledMinutes <= graceMinutes
  }

  /** How late a digest may still be useful; past this it is yesterday's news. */
  const val GRACE_MINUTES = 120
}
