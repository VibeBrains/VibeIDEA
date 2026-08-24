// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.history

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * Pure date helpers for the chat-history surfaces: the row badge date («Сегодня» /
 * «Вчера» / «23 авг») and the rail grouping («Сегодня» … «Ранее»). All functions take
 * `today` explicitly so boundaries are testable without touching the wall clock.
 *
 * Fence semantics (inclusive): a date exactly [RECENT_DAYS] days old still belongs to
 * «Последние 7 дней», exactly [MONTH_DAYS] days old — to «Последние 30 дней». Future
 * dates (clock skew between machines sharing the store) are clamped into «Сегодня».
 */
object HistoryDates {
  /** «Последние 7 дней» upper fence, inclusive. */
  const val RECENT_DAYS = 7L

  /** «Последние 30 дней» upper fence, inclusive. */
  const val MONTH_DAYS = 30L

  /** Short Russian month names, deliberately genitive-less («23 авг», «7 янв», «5 май»). */
  val MONTHS_SHORT_RU: List<String> =
    listOf("янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

  /** Rail group headers, in display order. */
  enum class Group(val title: String) {
    TODAY("Сегодня"),
    YESTERDAY("Вчера"),
    LAST_7("Последние 7 дней"),
    LAST_30("Последние 30 дней"),
    OLDER("Ранее"),
  }

  /** ISO-8601 instant → local calendar date; null when the stamp is absent or broken. */
  fun localDate(isoInstant: String, zone: ZoneId): LocalDate? =
    try {
      Instant.parse(isoInstant).atZone(zone).toLocalDate()
    }
    catch (ignored: DateTimeParseException) {
      null
    }

  /** Badge date: «Сегодня» / «Вчера» / «23 авг» (future dates render as «Сегодня»). */
  fun badgeLabel(date: LocalDate, today: LocalDate): String = when {
    !date.isBefore(today) -> Group.TODAY.title
    date == today.minusDays(1) -> Group.YESTERDAY.title
    else -> "${date.dayOfMonth} ${MONTHS_SHORT_RU[date.monthValue - 1]}"
  }

  /** Rail group for a date; see the fence semantics in the class doc. */
  fun groupOf(date: LocalDate, today: LocalDate): Group {
    if (!date.isBefore(today)) return Group.TODAY
    val age = ChronoUnit.DAYS.between(date, today)
    return when {
      age == 1L -> Group.YESTERDAY
      age <= RECENT_DAYS -> Group.LAST_7
      age <= MONTH_DAYS -> Group.LAST_30
      else -> Group.OLDER
    }
  }
}
