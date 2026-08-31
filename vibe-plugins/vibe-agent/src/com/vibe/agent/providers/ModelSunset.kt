// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import java.time.LocalDate

/**
 * The date a model stops being available, and what to do before it arrives.
 *
 * Access to a model normally ends on ANNOUNCEMENT, not on surprise: OpenAI told SpaceX on 29.08.2026
 * that models would stop being served through Cursor on 12.11.2026 — two and a half months of
 * notice. The whole value of that notice is lost if the date lives in a press release instead of in
 * the file that decides where requests go.
 *
 * So the date is a field of the model, and this object is the only place that reasons about it.
 * Pure and clock-free: today is an argument, because a rule that reads the system clock cannot be
 * tested on the day after tomorrow.
 */
object ModelSunset {
  /** How early to start warning. A month is enough to move, short enough not to become wallpaper. */
  const val WARN_DAYS = 30L

  enum class State {
    /** No date declared, or it is far away. */
    NONE,
    /** The date is within [WARN_DAYS]. */
    SOON,
    /** The date has passed: the model is not offered any more. */
    RETIRED,
  }

  /** ISO date (`2026-11-12`); anything else is treated as «не сказано» rather than as an error. */
  fun parse(date: String?): LocalDate? =
    date?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

  fun state(date: String?, today: LocalDate): State {
    val day = parse(date) ?: return State.NONE
    return when {
      // The day itself still counts as available: access ends AT the end of that date, and taking
      // the model away in the morning would cost a day of work for nothing.
      day.isBefore(today) -> State.RETIRED
      day.toEpochDay() - today.toEpochDay() <= WARN_DAYS -> State.SOON
      else -> State.NONE
    }
  }

  fun daysLeft(date: String?, today: LocalDate): Long? =
    parse(date)?.let { (it.toEpochDay() - today.toEpochDay()).coerceAtLeast(0) }

  /** A retired model is not offered — it stays in the file, so the person can see what happened. */
  fun isRetired(model: ModelEntry, today: LocalDate): Boolean = state(model.sunsetDate, today) == State.RETIRED

  /** One line per model worth mentioning: what goes away, where, and when. */
  data class Notice(val providerId: String, val modelId: String, val state: State, val daysLeft: Long)

  fun notices(providers: List<ProviderEntry>, today: LocalDate): List<Notice> =
    providers.flatMap { provider ->
      provider.models.mapNotNull { model ->
        val state = state(model.sunsetDate, today)
        if (state == State.NONE) return@mapNotNull null
        Notice(provider.id, model.id, state, daysLeft(model.sunsetDate, today) ?: 0)
      }
    }.sortedBy { it.daysLeft }
}
