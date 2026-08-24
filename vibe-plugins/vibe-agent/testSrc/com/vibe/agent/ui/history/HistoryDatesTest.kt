// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.history

import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryDatesTest {
  // A mid-month anchor: relative dates never cross a month boundary by accident.
  private val today: LocalDate = LocalDate.of(2026, 8, 23)

  @Test
  fun todayAndFutureRenderAsToday() {
    assertEquals("Сегодня", HistoryDates.badgeLabel(today, today))
    // Clock skew between machines sharing the store must not produce a weird future date.
    assertEquals("Сегодня", HistoryDates.badgeLabel(today.plusDays(3), today))
  }

  @Test
  fun yesterdayBoundaryIsExactlyOneDay() {
    assertEquals("Вчера", HistoryDates.badgeLabel(today.minusDays(1), today))
    assertEquals("21 авг", HistoryDates.badgeLabel(today.minusDays(2), today))
  }

  @Test
  fun badgeUsesAllTwelveRussianMonths() {
    val expected = listOf(
      "7 янв", "7 фев", "7 мар", "7 апр", "7 май", "7 июн",
      "7 июл", "7 авг", "7 сен", "7 окт", "7 ноя", "7 дек",
    )
    for (month in 1..12) {
      val date = LocalDate.of(2025, month, 7)
      assertEquals(expected[month - 1], HistoryDates.badgeLabel(date, today))
    }
  }

  @Test
  fun groupTodayCoversTodayAndFuture() {
    assertEquals(HistoryDates.Group.TODAY, HistoryDates.groupOf(today, today))
    assertEquals(HistoryDates.Group.TODAY, HistoryDates.groupOf(today.plusDays(1), today))
  }

  @Test
  fun groupYesterdayIsExactlyOneDay() {
    assertEquals(HistoryDates.Group.YESTERDAY, HistoryDates.groupOf(today.minusDays(1), today))
  }

  @Test
  fun sevenDayFenceIsInclusive() {
    // «Последние 7 дней» spans ages 2..7 inclusive.
    assertEquals(HistoryDates.Group.LAST_7, HistoryDates.groupOf(today.minusDays(2), today))
    assertEquals(HistoryDates.Group.LAST_7, HistoryDates.groupOf(today.minusDays(7), today))
    assertEquals(HistoryDates.Group.LAST_30, HistoryDates.groupOf(today.minusDays(8), today))
  }

  @Test
  fun thirtyDayFenceIsInclusive() {
    // «Последние 30 дней» spans ages 8..30 inclusive; 31 falls into «Ранее».
    assertEquals(HistoryDates.Group.LAST_30, HistoryDates.groupOf(today.minusDays(30), today))
    assertEquals(HistoryDates.Group.OLDER, HistoryDates.groupOf(today.minusDays(31), today))
  }

  @Test
  fun localDateConvertsInstantInGivenZone() {
    // 23:30 UTC on the 22nd is already the 23rd east of Greenwich.
    val instant = "2026-08-22T23:30:00Z"
    assertEquals(LocalDate.of(2026, 8, 23), HistoryDates.localDate(instant, ZoneId.of("Europe/Moscow")))
    assertEquals(LocalDate.of(2026, 8, 22), HistoryDates.localDate(instant, ZoneId.of("UTC")))
  }

  @Test
  fun localDateReturnsNullOnGarbage() {
    assertNull(HistoryDates.localDate("", ZoneId.of("UTC")))
    assertNull(HistoryDates.localDate("not-a-date", ZoneId.of("UTC")))
  }
}
