// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.budget

/**
 * Во что примерно обойдётся пайплайн — до того, как его запустят.
 *
 * Потолки расхода у нас есть (`SpendCeiling`: пять часов, неделя, месяц), но они срабатывают
 * ПОСЛЕ: человек узнаёт цену прогона, упершись в потолок на середине. Пайплайн из шести ролей
 * стоит в разы дороже одного хода, и разница видна только тому, кто уже платил.
 *
 * Смета строится на СВОЁМ журнале расхода, а не на выдуманных коэффициентах: сколько эта роль
 * стоила у вас в прошлый раз — единственное честное основание. Роль, которую ещё не гоняли,
 * называется отдельно; средним по другим ролям её подменять нельзя — «explore» и «qa» отличаются
 * в разы, и такое среднее было бы враньём с точностью до порядка.
 *
 * Чистая: журнал и описание пайплайна внутрь, смета наружу.
 */
object RunEstimate {
  /** Сколько прошлых прогонов роли берём в расчёт. Больше — это уже прошлогодние цены. */
  const val HISTORY_RUNS = 10

  data class RoleCost(val role: String, val avgTokens: Long, val runs: Int)

  data class Estimate(
    /** Оценка по ролям, у которых есть история. */
    val tokens: Long,
    val cost: Double?,
    val currency: String?,
    /** Роли, которые ещё ни разу не гоняли: их в сумме НЕТ, и об этом надо сказать. */
    val unknownRoles: List<String>,
    val knownSteps: Int,
    val totalSteps: Int,
  ) {
    /** Смета неполна: часть шагов оценить нечем, и итог заведомо занижен. */
    val partial: Boolean get() = unknownRoles.isNotEmpty()
  }

  /** Средний расход роли по последним прогонам. */
  fun roleCosts(entries: List<SpendLedger.Entry>, historyRuns: Int = HISTORY_RUNS): Map<String, RoleCost> =
    entries.filter { it.role != null }
      .groupBy { it.role!! }
      .mapValues { (role, rows) ->
        val recent = rows.sortedByDescending { it.atMs }.take(historyRuns)
        RoleCost(role, recent.sumOf { it.tokens } / recent.size.coerceAtLeast(1), recent.size)
      }

  /**
   * Смета прогона.
   *
   * Цена считается по той же цене, что и учёт расхода, — из `providers.json`; её может не быть, и
   * тогда смета остаётся в токенах. Число без валюты честнее выдуманной валюты.
   */
  fun of(
    steps: List<String>,
    entries: List<SpendLedger.Entry>,
    pricePerMillionInput: Double? = null,
    currency: String? = null,
    historyRuns: Int = HISTORY_RUNS,
  ): Estimate {
    val costs = roleCosts(entries, historyRuns)
    val known = steps.filter { costs.containsKey(it) }
    val unknown = steps.filterNot { costs.containsKey(it) }.distinct()
    val tokens = known.sumOf { costs.getValue(it).avgTokens }
    val cost = pricePerMillionInput?.takeIf { it > 0 && tokens > 0 }?.let { tokens * it / MILLION }
    return Estimate(
      tokens = tokens,
      cost = cost,
      currency = if (cost != null) currency else null,
      unknownRoles = unknown,
      knownSteps = known.size,
      totalSteps = steps.size,
    )
  }

  private const val MILLION = 1_000_000.0
}
