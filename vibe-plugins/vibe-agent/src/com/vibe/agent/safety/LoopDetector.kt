// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.safety

/**
 * Notices that the agent is going in circles, and says so before the circle costs a day of tokens.
 *
 * Two shapes of loop, and they look different from the inside:
 * - REPEAT: the same call with the same arguments, over and over. Usually a tool that fails in a
 *   way the model does not understand, so it tries again identically, expecting a different world;
 * - CYCLE: read A, edit B, read A, edit B… Each step is different from the previous one, so a
 *   naive "same as last time" check sees nothing, and this is exactly the shape that runs for
 *   hours. It is found by looking for a repeating period, not for repeated neighbours.
 *
 * The detector is pure and holds only fingerprints: what matters is the SHAPE of the sequence, and
 * keeping arguments would turn a safety net into a second copy of the conversation.
 */
object LoopDetector {
  /** Identical call this many times in a row is no longer a retry. */
  const val REPEAT_THRESHOLD = 4

  /** A repeating period of 2..4 seen this many times is a cycle. */
  const val CYCLE_REPEATS = 3
  const val MAX_CYCLE_PERIOD = 4

  /** Longer history buys nothing: a loop shows itself within a few dozen calls or it is not one. */
  const val HISTORY_LIMIT = 64

  enum class Verdict { OK, REPEAT, CYCLE }

  data class Finding(val verdict: Verdict, val fingerprint: String, val count: Int)

  /** Call identity for loop purposes: the tool and its arguments, not the prose around them. */
  fun fingerprint(toolName: String?, arguments: String?): String =
    (toolName.orEmpty().trim().lowercase() + "|" + arguments.orEmpty().trim()).take(FINGERPRINT_LIMIT)

  /** Bounded history of fingerprints in call order. */
  class History(private val limit: Int = HISTORY_LIMIT) {
    private val calls = ArrayDeque<String>()

    @Synchronized
    fun add(fingerprint: String) {
      calls.addLast(fingerprint)
      while (calls.size > limit) calls.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = calls.toList()

    @Synchronized
    fun clear() = calls.clear()

    @Synchronized
    fun size(): Int = calls.size
  }

  fun check(
    calls: List<String>,
    repeatThreshold: Int = REPEAT_THRESHOLD,
    cycleRepeats: Int = CYCLE_REPEATS,
  ): Finding {
    if (calls.isEmpty()) return Finding(Verdict.OK, "", 0)

    // REPEAT: the tail is the same call N times.
    val last = calls.last()
    var same = 0
    for (call in calls.asReversed()) {
      if (call != last) break
      same++
    }
    if (same >= repeatThreshold) return Finding(Verdict.REPEAT, last, same)

    // CYCLE: the tail is a period repeated N times. Periods are tried shortest first, so
    // «A B A B A B» is reported as a period of two rather than of four.
    for (period in 2..MAX_CYCLE_PERIOD) {
      val needed = period * cycleRepeats
      if (calls.size < needed) continue
      val tail = calls.takeLast(needed)
      val pattern = tail.take(period)
      // A "cycle" of one repeated call is a REPEAT, and was already reported as one.
      if (pattern.distinct().size == 1) continue
      val repeats = tail.chunked(period).all { it == pattern }
      if (repeats) return Finding(Verdict.CYCLE, pattern.joinToString(" → "), cycleRepeats)
    }
    return Finding(Verdict.OK, "", 0)
  }

  private const val FINGERPRINT_LIMIT = 400
}
