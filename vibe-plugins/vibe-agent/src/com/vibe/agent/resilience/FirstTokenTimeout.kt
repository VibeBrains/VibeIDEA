// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.resilience

import java.net.SocketTimeoutException

/**
 * How long a model may think before its first fragment, and why one constant is not enough.
 *
 * A thinking model and a hung one look alike: no tokens, the same spinner. A fixed patience therefore
 * fails both ways — short enough to notice a hang also cuts a long conversation being re-read, and the
 * cut is not free: the retry starts from scratch, so the model thinks the whole 40 s again, and after
 * three attempts the person gets «Модель не ответила» about a model that was working the whole time.
 *
 * So the first-fragment wait is derived from what is known about this exact request: how much prompt the
 * model has to read, how slowly it has been starting lately, and which attempt this is. Everything
 * between fragments keeps the old, flat patience — a stream that went quiet mid-answer is a different
 * fault, and it is not cured by a longer first-fragment wait.
 *
 * Pure: the settings, the prompt size and the history come in as arguments.
 */
object FirstTokenTimeout {
  /** What a person sets: 30 s, the VibeIDE default for the first fragment of a turn */
  const val DEFAULT_SETTING_MS = 30_000L

  /** Setting zero would mean «do not wait at all», which no model survives: the floor stays at the default */
  const val MIN_SETTING_MS = 30_000L

  /** Prompt size above which the wait grows: a long thread has to be read before the first word */
  const val PROMPT_STEP_TOKENS = 50_000L

  /** How much each full step of prompt beyond [PROMPT_STEP_TOKENS] adds to the wait */
  const val PROMPT_STEP_MS = 10_000L

  /** The slowest recent start of this model, times this, is the floor — 1.5 times is what VibeIDE chose */
  const val SLOWEST_FACTOR_NUM = 3L
  const val SLOWEST_FACTOR_DEN = 2L

  /** How many recent first-fragment delays of one model count towards its floor */
  const val HISTORY_SIZE = 10

  /** The hard ceiling: past it the turn stops instead of waiting, however slow the model has been */
  const val HARD_CEILING_MS = 300_000L

  /**
   * The wait for the first fragment of attempt [retry], 0-based.
   *
   * The floor is the larger of the person's setting and 1.5 times the slowest recent start of this model;
   * every repeat multiplies that floor by `1 + retry`, because a model that already failed to start once
   * is not expected to start faster the second time. The prompt's extra is added on top and the hard
   * ceiling caps the result — a wait nobody can explain is worse than an honest refusal.
   */
  fun limit(settingMs: Long, promptTokens: Long?, recentFirstTokenMs: List<Long>, retry: Int): Long {
    val setting = settingMs.coerceAtLeast(MIN_SETTING_MS)
    val slowest = recentFirstTokenMs.maxOrNull() ?: 0L
    val knownFloor = slowest * SLOWEST_FACTOR_NUM / SLOWEST_FACTOR_DEN
    val floor = maxOf(setting, knownFloor)
    val grown = floor * (1 + retry.coerceAtLeast(0))
    return (grown + promptExtra(promptTokens)).coerceAtMost(HARD_CEILING_MS)
  }

  /** `+10 s` for every started 50k of prompt past the first 50k; a short prompt adds nothing */
  fun promptExtra(promptTokens: Long?): Long {
    val tokens = promptTokens ?: 0L
    if (tokens <= PROMPT_STEP_TOKENS) return 0
    val steps = (tokens - PROMPT_STEP_TOKENS + PROMPT_STEP_TOKENS - 1) / PROMPT_STEP_TOKENS
    return steps * PROMPT_STEP_MS
  }

  /**
   * The first-fragment delays of recent turns, per model.
   *
   * Kept in memory on purpose: the point is the model's mood right now, and a delay from last week says
   * nothing about a provider that has since changed hands. Bound, so a long session cannot grow it.
   */
  class History(private val size: Int = HISTORY_SIZE) {
    private val perModel = LinkedHashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun note(modelKey: String, delayMs: Long) {
      if (delayMs <= 0) return
      val delays = perModel.getOrPut(modelKey) { ArrayDeque() }
      delays.addLast(delayMs)
      while (delays.size > size) delays.removeFirst()
    }

    /** The recent delays of [modelKey], oldest first; empty when the model has not answered here yet */
    @Synchronized
    fun recent(modelKey: String): List<Long> = perModel[modelKey]?.toList() ?: emptyList()
  }
}

/**
 * The attempt gave up waiting for its first fragment.
 *
 * A [SocketTimeoutException] on purpose: to [RetryPolicy] this is the same class of fault as any other
 * read timeout — worth repeating — while the message carries the real wait, so the person is told what
 * actually happened instead of a constant that never applied to their model.
 */
class FirstTokenTimeoutException(
  val waitedMs: Long,
  val limitMs: Long,
  message: String,
) : SocketTimeoutException(message)
