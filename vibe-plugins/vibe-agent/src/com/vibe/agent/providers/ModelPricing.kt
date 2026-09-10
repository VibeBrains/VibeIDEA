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
  /**
   * Надбавка за длинный промпт, если вендор её объявил.
   *
   * Повод — GPT-6 Astra: «Prompts with more than 272K input tokens are priced at 2x input and cache
   * rates and 1.5x output **for the full request**» (developers.openai.com, проверено 10.09.2026).
   * Без неё отчёт о расходе на длинном контексте занижает счёт вдвое и молчит об этом — а именно
   * агентный цикл, перечитывающий контекст, в этот порог и упирается.
   */
  val longContext: LongContext? = null,
) {
  /**
   * Множители, действующие, когда промпт длиннее порога.
   *
   * Множители, а не вторая таблица цен: вендор объявляет именно «в столько-то раз», и вторая
   * таблица разошлась бы с первой при первом же изменении базовой ставки.
   */
  data class LongContext(
    /** Порог по длине промпта. Считается по отправленному: свежий вход плюс чтение из кэша. */
    val overInputTokens: Long,
    val input: Double = 1.0,
    val cache: Double = 1.0,
    val output: Double = 1.0,
  ) {
    val stated: Boolean get() = overInputTokens > 0 && (input != 1.0 || cache != 1.0 || output != 1.0)
  }

  /**
   * Действуют ли надбавки на этом ходе.
   *
   * Порог меряется по ПРОМПТУ — свежий вход плюс чтение из кэша: вендор говорит «prompts with more
   * than N input tokens», а кэшированная часть промпта тоже отправлена. Выход в порог не входит:
   * он ещё не существует в момент, когда цена определяется.
   */
  fun longContextApplies(usage: TokenUsage): Boolean {
    val tier = longContext?.takeIf { it.stated } ?: return false
    return usage.inputTokens + usage.cacheReadTokens > tier.overInputTokens
  }
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
    // Надбавка действует на ВЕСЬ запрос, а не на превышение: так объявлено вендором
    // («for the full request»), и считать иначе значит выдумать свою тарифную сетку.
    val tier = longContext.takeIf { longContextApplies(usage) }
    val fIn = tier?.input ?: 1.0
    val fCache = tier?.cache ?: 1.0
    val fOut = tier?.output ?: 1.0
    return usage.inputTokens * input * fIn / MILLION +
           usage.outputTokens * output * fOut / MILLION +
           usage.cacheReadTokens * cacheRead * fCache / MILLION +
           usage.cacheWriteTokens * cacheWrite * fCache / MILLION
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
    // Те же множители, что и в счёте: экономия, посчитанная по базовой ставке при действующей
    // надбавке, назвала бы число, которого не было ни в одном счёте.
    val tier = longContext.takeIf { longContextApplies(usage) }
    val saved = input * (tier?.input ?: 1.0) - cacheRead * (tier?.cache ?: 1.0)
    return usage.cacheReadTokens * saved / MILLION
  }

  companion object {
    const val MILLION = 1_000_000.0
    const val DEFAULT_CURRENCY = "USD"
  }
}
