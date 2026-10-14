// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.resilience

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

/**
 * A wait that a stop ends at once
 *
 * A plain sleep in steps notices a stop at the next step; here [wake] ends the wait the moment the stop is pressed
 * The steps stay as a safety net for a cancellation that never calls [wake]
 */
class InterruptiblePause(private val stepMs: Long = DEFAULT_STEP_MS) {
  private val lock = ReentrantLock()
  private val woken = lock.newCondition()

  /** Ends a wait in progress; [cancelled] is asked again and decides whether it was a stop */
  fun wake() = lock.withLock { woken.signalAll() }

  /** True when [delayMs] passed in full, false when [cancelled] became true first */
  fun await(delayMs: Long, cancelled: () -> Boolean): Boolean {
    var left = TimeUnit.MILLISECONDS.toNanos(delayMs)
    val step = TimeUnit.MILLISECONDS.toNanos(stepMs)
    lock.withLock {
      while (left > 0) {
        if (cancelled()) return false
        val chunk = min(left, step)
        left -= chunk - woken.awaitNanos(chunk)
      }
    }
    return !cancelled()
  }

  private companion object {
    const val DEFAULT_STEP_MS = 250L
  }
}
