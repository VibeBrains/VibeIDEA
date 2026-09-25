// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.slop

import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * How long one rule may match a text, and which rules already ran out of that time
 *
 * A project's `.vibe/slop.json` may carry any regular expression, and one with nested repetition backtracks
 * exponentially: `((a+)+)+b` doubles its time with every character, and forty characters take hours
 * The check runs on the IDE's own threads: the editor action, the turn gate, the MCP tool, the design detector
 * One such rule in a cloned repository would hang each of them
 *
 * Every read of the text checks the clock ([DeadlineText]), which stops a runaway match without a thread per rule
 * A rule past its time is dropped from this check and from every later one sharing the budget:
 * The design detector checks each element of a page, and paying the time again per element would be the same hang
 * The surface names the dropped rules: a check that quietly skipped a rule would read cleaner than the text is
 *
 * One budget is meant for one round of checking (a command, a turn, a page), not for the life of the IDE:
 * A rule that ran out on a huge file may be fine on the next small one
 */
class SlopBudget(
  val ruleMillis: Long = DEFAULT_RULE_MILLIS,
  private val isCancelled: () -> Boolean = { false },
) {
  private val exhausted: MutableSet<String> = ConcurrentHashMap.newKeySet()

  /** Rules dropped for running out of time, sorted for a stable message */
  val skipped: List<String> get() = exhausted.sorted()

  internal fun spent(rule: String): Boolean = rule in exhausted

  internal fun exhaust(rule: String) {
    exhausted += rule
  }

  /** A fresh deadline for one rule, starting now */
  internal fun clock(): Clock = Clock(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ruleMillis), isCancelled)

  internal class Clock(private val deadline: Long, private val isCancelled: () -> Boolean) {
    fun guard(text: CharSequence): CharSequence = DeadlineText(text, this)

    fun check() {
      if (isCancelled()) throw CancellationException()
      if (System.nanoTime() - deadline > 0 || Thread.currentThread().isInterrupted) throw Overrun()
    }
  }

  /** The rule ran past its time; carries no stack, it is thrown from inside the regex engine on every overrun */
  internal class Overrun : RuntimeException(null, null, false, false)

  companion object {
    /**
     * The shipped catalogue checks a text of a megabyte in under three seconds with all its rules together,
     * So a single sound rule never comes near this, and a runaway one is cut before a person gives up waiting
     */
    const val DEFAULT_RULE_MILLIS = 5_000L
  }
}

/**
 * Text whose reads check the clock: the regex engine reads through `charAt`, so a runaway match stops here
 * The clock is read once per [CHECK_EVERY] reads: a clock read per character would slow every sound rule for nothing
 */
internal class DeadlineText(private val text: CharSequence, private val clock: SlopBudget.Clock) : CharSequence {
  private var reads = 0

  override val length: Int get() = text.length

  override fun get(index: Int): Char {
    if (++reads and (CHECK_EVERY - 1) == 0) clock.check()
    return text[index]
  }

  override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
    DeadlineText(text.subSequence(startIndex, endIndex), clock)

  override fun toString(): String = text.toString()

  private companion object {
    /** A power of two, so the check is a mask rather than a division */
    const val CHECK_EVERY = 1024
  }
}
