// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.background

/**
 * How long a background job may live and how often it says something.
 *
 * Borrowed from the MCP Tasks extension — the one idea in it that needs no MCP at all. That spec
 * makes a server hand back `ttlMs` and `pollIntervalMs` with every long job, and the reason is the
 * one we already had without noticing: a job with no declared lifetime is indistinguishable from a
 * hung one, and a job that reports nothing is indistinguishable from a job doing nothing.
 *
 * Our `/bg` had neither. It inherited a fifteen-minute timeout meant for a measurement, said
 * nothing for the whole run, and on a genuinely long build it would kill the work mid-way with a
 * message about measurement.
 *
 * Pure arithmetic over milliseconds: no clock inside, so the decisions are testable.
 */
object TaskLimits {
  /**
   * Default lifetime of a background command.
   *
   * Half an hour is longer than any check worth watching and shorter than a night: the point is
   * not to guess the job's duration but to guarantee it ends and says so.
   */
  const val DEFAULT_TTL_MS: Long = 30L * 60 * 1000

  /** Silence longer than this reads as a hang, whatever is really happening. */
  const val DEFAULT_POLL_MS: Long = 60L * 1000

  data class Limits(val ttlMs: Long = DEFAULT_TTL_MS, val pollIntervalMs: Long = DEFAULT_POLL_MS) {
    /** Zero means «без ограничения» — an explicit choice, not an accident. */
    val unlimited: Boolean get() = ttlMs <= 0
  }

  /** Has the job outlived its declared lifetime? */
  fun expired(startedAtMs: Long, nowMs: Long, limits: Limits): Boolean =
    !limits.unlimited && nowMs - startedAtMs >= limits.ttlMs

  /**
   * Is it time to say the job is still running?
   *
   * Counted from the LAST word rather than from the start, so a long job produces a steady
   * heartbeat instead of a burst of overdue notices.
   */
  fun progressDue(lastReportMs: Long, nowMs: Long, limits: Limits): Boolean =
    limits.pollIntervalMs > 0 && nowMs - lastReportMs >= limits.pollIntervalMs

  /**
   * How long the job has left, in whole seconds, or null when it may run forever.
   *
   * Never negative: a job past its deadline has zero left, and «-3 секунды» is not a thing anyone
   * needs to read.
   */
  fun remainingSeconds(startedAtMs: Long, nowMs: Long, limits: Limits): Long? {
    if (limits.unlimited) return null
    return ((limits.ttlMs - (nowMs - startedAtMs)) / 1000).coerceAtLeast(0)
  }
}
