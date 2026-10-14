// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.resilience

/**
 * One pause before a request is sent again, as the chat shows it
 *
 * [attempt] is the number of the repeat that is about to start (from 1) out of [retries]
 * [statusCode] the HTTP status of the failed attempt, null when there was no response at all
 * [detail] is the provider's own words, for whoever wants to read them
 */
data class RetryPause(
  val attempt: Int,
  val retries: Int,
  val delayMs: Long,
  val statusCode: Int?,
  val kind: RetryPolicy.Kind,
  val detail: String?,
)

/**
 * Whoever shows the pauses of a request: told when one starts and when it is over
 *
 * Called on the thread that makes the request, so an implementation hands the work to the UI thread itself
 */
interface RetryObserver {
  fun onPause(pause: RetryPause) {}

  /** The pause is over: the time passed, or the request was stopped during it */
  fun onResume() {}

  companion object {
    val NONE: RetryObserver = object : RetryObserver {}
  }
}
