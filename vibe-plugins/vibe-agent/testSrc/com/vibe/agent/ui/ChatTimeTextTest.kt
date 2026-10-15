// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The date a message's clock time hides in its tooltip */
class ChatTimeTextTest {
  @Test
  fun `the tooltip carries the date and the time in the person's zone`() {
    val tip = ChatTimeText.dateTooltip("2026-10-14T09:30:00Z", ZoneId.of("UTC"), Locale.ENGLISH)
    assertNotNull(tip)
    assertTrue("2026" in tip, tip)
    assertTrue("9:30" in tip, tip)
    assertTrue("14" in tip, tip)
  }

  @Test
  fun `the zone moves the time, not the date format`() {
    val tip = ChatTimeText.dateTooltip("2026-10-14T09:30:00Z", ZoneId.of("Asia/Tokyo"), Locale.ENGLISH)
    assertNotNull(tip)
    assertTrue("6:30" in tip, tip)
  }

  @Test
  fun `a record without a usable stamp has no tooltip`() {
    assertNull(ChatTimeText.dateTooltip(null))
    assertNull(ChatTimeText.dateTooltip(""))
    assertNull(ChatTimeText.dateTooltip("yesterday"))
  }

  @Test
  fun `every message carries the date with its time`() {
    val instant = java.time.Instant.parse("2026-10-15T09:55:00Z")
    assertEquals("15.10.2026 12:55", ChatTimeText.stamp(instant, ZoneId.of("Europe/Moscow")))
    assertEquals("05.01.2026 03:07", ChatTimeText.stamp(java.time.Instant.parse("2026-01-05T03:07:00Z"), ZoneId.of("UTC")))
  }
}
