// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.budget

import java.util.concurrent.atomic.AtomicReference

/**
 * Turns a running total into what it added.
 *
 * Providers report usage cumulatively — «за эту сессию потрачено столько», resent with every
 * progress update — and a ledger adds up increments. Mixing the two is a bug that hides for a long
 * time and then arrives all at once: the tokens were being converted here and the cost was not, so
 * every update re-added the whole running total. Nobody noticed while money was a column in a
 * report; a spending ceiling reading that column would have refused work on the first turn.
 *
 * Two rules, both learned from the token side:
 * - a decrease is not a refund. A new turn starts the counter lower, and subtracting would make
 *   the day's spend silently shrink; the floor at zero is what keeps the ledger monotonic.
 * - a zero increment is not an event. Recording it would fill the ledger with rows that mean
 *   «ничего не изменилось».
 */
class CumulativeMeter {
  private val seen = AtomicReference(0.0)

  /** What this report added, or null when it added nothing. */
  fun advance(total: Double?): Double? {
    if (total == null) return null
    val previous = seen.getAndSet(total)
    val added = total - previous
    return if (added > 0.0) added else null
  }

  /** The same for whole units, where «added nothing» is an honest zero rather than null. */
  fun advanceWhole(total: Long): Long {
    val previous = seen.getAndSet(total.toDouble())
    return (total - previous.toLong()).coerceAtLeast(0)
  }

  fun reset() = seen.set(0.0)
}
