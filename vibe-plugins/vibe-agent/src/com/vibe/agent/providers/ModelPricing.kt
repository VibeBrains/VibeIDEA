// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoField

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
  /**
   * Price by the hour, when the vendor declared it: the rates above are PEAK rates, and outside the
   * peak every rate is multiplied by [TimeOfDay.offPeakFactor].
   *
   * The occasion is DeepSeek from 10.09.2026: peak 01:00–04:00 and 06:00–10:00 UTC on weekdays,
   * half the peak rate at any other time (api-docs.deepseek.com/quick_start/pricing, checked
   * 11.09.2026). Without it the spending report is off by a factor of two, in a direction that
   * depends on the hour of the turn.
   */
  val timeOfDay: TimeOfDay? = null,
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
   * The peak schedule. Rates of the entry are the peak ones; outside the peak windows, and on days
   * without a peak, every rate is multiplied by [offPeakFactor].
   *
   * Peak rather than off-peak as the base: both vendors that publish such a schedule (DeepSeek,
   * Z.ai) state the off-peak price as a share of the peak one, and the entry repeats their price
   * list instead of recomputing it.
   */
  data class TimeOfDay(
    val peakWindows: List<Window>,
    /** Days on which the peak windows apply; empty means every day. */
    val peakDays: Set<DayOfWeek> = emptySet(),
    val offPeakFactor: Double = 1.0,
  ) {
    /** Minutes since midnight UTC, end exclusive; a window across midnight has [toMinute] below [fromMinute]. */
    data class Window(val fromMinute: Int, val toMinute: Int) {
      fun contains(minute: Int): Boolean =
        if (fromMinute < toMinute) minute in fromMinute until toMinute else minute >= fromMinute || minute < toMinute
    }

    val stated: Boolean get() = peakWindows.isNotEmpty() && offPeakFactor > 0 && offPeakFactor != 1.0

    fun isPeak(at: Instant): Boolean {
      val utc = at.atOffset(ZoneOffset.UTC)
      if (peakDays.isNotEmpty() && utc.dayOfWeek !in peakDays) return false
      val minute = utc.get(ChronoField.MINUTE_OF_DAY)
      return peakWindows.any { it.contains(minute) }
    }

    /**
     * The multiplier to the rates at [at]; null means the moment is unknown and counts as peak.
     *
     * Peak rather than some average: without the moment of the turn the honest answer is the
     * declared rate, not an invented share of hours — and an overestimate is the safe error for a
     * spending ceiling, an underestimate is not.
     */
    fun factorAt(at: Instant?): Double = if (at == null || !stated || isPeak(at)) 1.0 else offPeakFactor
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
   *
   * [at] is the moment of the turn, for the price by the hour; null when it is unknown — then the
   * declared (peak) rate applies, see [TimeOfDay.factorAt]. There is no default on purpose: a caller
   * that forgets the moment would silently price every off-peak turn at twice its cost.
   */
  fun costOf(usage: TokenUsage, at: Instant?): Double? {
    if (!stated || !usage.known) return null
    // Надбавка действует на ВЕСЬ запрос, а не на превышение: так объявлено вендором
    // («for the full request»), и считать иначе значит выдумать свою тарифную сетку.
    val tier = longContext.takeIf { longContextApplies(usage) }
    val fIn = tier?.input ?: 1.0
    val fCache = tier?.cache ?: 1.0
    val fOut = tier?.output ?: 1.0
    val hour = timeOfDay?.factorAt(at) ?: 1.0
    return (usage.inputTokens * input * fIn +
            usage.outputTokens * output * fOut +
            usage.cacheReadTokens * cacheRead * fCache +
            usage.cacheWriteTokens * cacheWrite * fCache) * hour / MILLION
  }

  /**
   * What the cache saved on this turn, or null when it cannot be said.
   *
   * The number worth showing is not «сколько стоило», it is «сколько стоило бы без кэша»: cache
   * reads are the one line item a person can act on by keeping the conversation append-only.
   */
  fun cacheSavingOf(usage: TokenUsage, at: Instant?): Double? {
    if (!stated || usage.cacheReadTokens <= 0) return null
    if (input <= 0) return null
    // Те же множители, что и в счёте: экономия, посчитанная по базовой ставке при действующей
    // надбавке, назвала бы число, которого не было ни в одном счёте.
    val tier = longContext.takeIf { longContextApplies(usage) }
    val hour = timeOfDay?.factorAt(at) ?: 1.0
    val saved = (input * (tier?.input ?: 1.0) - cacheRead * (tier?.cache ?: 1.0)) * hour
    return usage.cacheReadTokens * saved / MILLION
  }

  companion object {
    const val MILLION = 1_000_000.0
    const val DEFAULT_CURRENCY = "USD"
  }
}
