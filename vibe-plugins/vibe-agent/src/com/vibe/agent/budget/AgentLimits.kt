// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.budget

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Spending ceilings of one external agent, declared in its `.vibe/agents.json` entry.
 *
 * Names agreed with VibeIDE (13.09.2026): `limits: { costPerDay, costPerRun, currency, tokensPerDay }`.
 * The role budget answers «how much may a reviewer spend»; this answers «how much may THIS agent
 * spend», which is the question when an agent calls other models and nobody is watching the bill.
 *
 * Money is compared only in the stated currency: a ceiling in USD against a spend reported in another
 * currency is not converted — it is not checked, and that is said, because a silent conversion by a
 * guessed rate would be a ceiling nobody set. Without a price the token ceiling is the one that works.
 *
 * Pure: the entry in, a verdict out; the sums come from the spend ledger.
 */
data class AgentLimits(
  val costPerDay: Double? = null,
  val costPerRun: Double? = null,
  val currency: String? = null,
  val tokensPerDay: Long? = null,
) {
  val any: Boolean get() = costPerDay != null || costPerRun != null || tokensPerDay != null

  enum class Reason { COST_PER_DAY, COST_PER_RUN, TOKENS_PER_DAY }

  data class Exceeded(val reason: Reason, val spent: Double, val limit: Double)

  /**
   * The first ceiling already reached, or null.
   *
   * Checked BEFORE a turn: a ceiling reported after the spend is a receipt. «Reached» counts — at the
   * limit the next turn would already be over it.
   */
  fun exceeded(
    tokensToday: Long,
    costToday: Double?,
    costTodayCurrency: String?,
    costThisRun: Double?,
    costThisRunCurrency: String?,
  ): Exceeded? {
    tokensPerDay?.let { limit -> if (tokensToday >= limit) return Exceeded(Reason.TOKENS_PER_DAY, tokensToday.toDouble(), limit.toDouble()) }
    costPerDay?.let { limit ->
      if (costToday != null && sameCurrency(costTodayCurrency) && costToday >= limit) return Exceeded(Reason.COST_PER_DAY, costToday, limit)
    }
    costPerRun?.let { limit ->
      if (costThisRun != null && sameCurrency(costThisRunCurrency) && costThisRun >= limit) return Exceeded(Reason.COST_PER_RUN, costThisRun, limit)
    }
    return null
  }

  /** No currency on the limit means «whatever the provider reports»; a stated one must match. */
  private fun sameCurrency(reported: String?): Boolean =
    currency == null || reported == null || currency.equals(reported, ignoreCase = true)

  companion object {
    fun parse(element: kotlinx.serialization.json.JsonElement?): AgentLimits? {
      val obj = element as? JsonObject ?: return null
      fun num(key: String): Double? = (obj[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 }
      val limits = AgentLimits(
        costPerDay = num("costPerDay"),
        costPerRun = num("costPerRun"),
        currency = (obj["currency"] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null },
        tokensPerDay = (obj["tokensPerDay"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 },
      )
      return limits.takeIf { it.any }
    }
  }
}
