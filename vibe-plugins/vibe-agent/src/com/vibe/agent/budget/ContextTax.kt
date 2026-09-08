// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.budget

import com.vibe.agent.providers.ModelPricing
import com.vibe.agent.providers.TokenUsage

/**
 * Сколько разговор платит за ПОВТОРНУЮ подачу того, что уже было сказано.
 *
 * Счёт за агентную работу состоит не из ответов. Каждый ход заново отправляет весь накопленный
 * контекст: прочитанные файлы, вывод инструментов, предыдущие сообщения. Ответ на десять строк в
 * тридцатом ходе тащит за собой всё, что агент прочитал в первом, — и в отчёте это выглядит как
 * «модель дорогая», хотя дорогой была привычка читать файлы целиком.
 *
 * Замер устроен нарочито просто и воспроизводимо: сумма входных токенов (включая прочитанные из
 * кэша) против суммы выходных. Отношение первого ко второму и есть налог: во сколько раз разговор
 * заплатил за напоминание против того, сколько заплатил за работу.
 *
 * Что этот замер НЕ делает: не судит. Высокий налог законен там, где задача про большой файл, и
 * позорен там, где агент двадцать ходов перечитывал один и тот же каталог. Различить их может
 * только человек, поэтому здесь считаются числа, а вывод остаётся ему.
 *
 * Чистый и без часов: список ходов приходит снаружи.
 */
object ContextTax {
  data class Report(
    val turns: Int,
    val inputTokens: Long,
    val cacheReadTokens: Long,
    val outputTokens: Long,
    /** Стоимость входа (вместе с чтением кэша), если цена модели названа человеком. */
    val inputCost: Double?,
    /** Стоимость выхода при той же цене. */
    val outputCost: Double?,
  ) {
    /** Во сколько раз вход дороже выхода по токенам; null — выхода не было, делить не на что. */
    val ratio: Double? get() = if (outputTokens > 0) (inputTokens + cacheReadTokens).toDouble() / outputTokens else null

    /** Средний резидентный контекст на ход — то число, которое растёт незаметно. */
    val perTurn: Long get() = if (turns > 0) (inputTokens + cacheReadTokens) / turns else 0
  }

  /**
   * Свод по ходам одного разговора.
   *
   * Ходы без чисел от провайдера пропускаются, а не считаются нулями: догадка в этом отчёте хуже
   * пропуска — она занижает налог ровно там, где провайдер промолчал.
   */
  fun of(usages: List<TokenUsage>, pricing: ModelPricing? = null): Report {
    val known = usages.filter { it.known }
    val input = known.sumOf { it.inputTokens }
    val cacheRead = known.sumOf { it.cacheReadTokens }
    val output = known.sumOf { it.outputTokens }
    val priced = pricing?.takeIf { it.stated }
    return Report(
      turns = known.size,
      inputTokens = input,
      cacheReadTokens = cacheRead,
      outputTokens = output,
      inputCost = priced?.let { it.input * input / MILLION + it.cacheRead * cacheRead / MILLION },
      outputCost = priced?.let { it.output * output / MILLION },
    )
  }

  private const val MILLION = 1_000_000.0
}
