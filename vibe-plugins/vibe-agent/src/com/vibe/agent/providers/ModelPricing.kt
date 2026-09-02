// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

/**
 * What a million tokens of this model costs, as the owner of the key wrote it down.
 *
 * We deliberately keep no price list of our own. A table in our code is wrong the day a vendor
 * changes one line, and a ceiling that stops work on an invented number stops it for an invented
 * reason. But a price the person typed themselves is a fact about their contract, and refusing to
 * apply it leaves the whole money side of the product working only for providers that happen to
 * report cost in their responses.
 *
 * Cache reads are a separate rate rather than a discount factor: on Claude Fable 5.1 they cost
 * 0.025 of base input where other Claude models charge 0.1, and a single "cacheDiscount" number
 * would have to be re-derived every time a vendor moves it.
 */
data class ModelPricing(
  /** Per million tokens. Zero means «not stated», not «free». */
  val input: Double = 0.0,
  val output: Double = 0.0,
  val cacheRead: Double = 0.0,
  val cacheWrite: Double = 0.0,
  val currency: String = DEFAULT_CURRENCY,
) {
  val stated: Boolean get() = input > 0 || output > 0 || cacheRead > 0 || cacheWrite > 0

  /**
   * What this usage costs, or null when the price is not stated.
   *
   * Null rather than zero: «бесплатно» and «неизвестно» are different answers, and a report that
   * shows a free turn where it means an unknown one teaches people to distrust the whole column.
   *
   * A rate left at zero while others are set counts as zero for its part — the person who wrote
   * only `input` and `output` said what they knew, and refusing the whole calculation over a
   * missing cache rate would answer a question they did ask with silence.
   */
  fun costOf(usage: TokenUsage): Double? {
    if (!stated || !usage.known) return null
    return usage.inputTokens * input / MILLION +
           usage.outputTokens * output / MILLION +
           usage.cacheReadTokens * cacheRead / MILLION +
           usage.cacheWriteTokens * cacheWrite / MILLION
  }

  /**
   * What the cache saved on this turn, or null when it cannot be said.
   *
   * The number worth showing is not «сколько стоило», it is «сколько стоило бы без кэша»: cache
   * reads are the one line item a person can act on by keeping the conversation append-only.
   */
  fun cacheSavingOf(usage: TokenUsage): Double? {
    if (!stated || usage.cacheReadTokens <= 0) return null
    if (input <= 0) return null
    return usage.cacheReadTokens * (input - cacheRead) / MILLION
  }

  companion object {
    const val MILLION = 1_000_000.0
    const val DEFAULT_CURRENCY = "USD"
  }
}
