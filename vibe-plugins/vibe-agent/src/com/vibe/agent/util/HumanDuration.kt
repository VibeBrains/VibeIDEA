// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.util

import com.vibe.agent.i18n.VibeI18n.t

/**
 * A stretch of time as a person reads it.
 *
 * Whole minutes alone lie at both ends: eleven seconds of silence became "0 min", and a clock that
 * had never been started became "29828727 min" — the number the owner saw instead of "11 s"
 * (18.09.2026). Seconds below a minute, minutes and seconds below an hour, hours and minutes above.
 */
object HumanDuration {
  private const val SECOND_MS = 1_000L
  private const val MINUTE_MS = 60 * SECOND_MS
  private const val HOUR_MS = 60 * MINUTE_MS

  fun text(millis: Long): String {
    val ms = millis.coerceAtLeast(0)
    return when {
      ms < MINUTE_MS -> t("duration.seconds", "seconds" to ms / SECOND_MS)
      ms < HOUR_MS -> t("duration.minutes", "minutes" to ms / MINUTE_MS, "seconds" to (ms % MINUTE_MS) / SECOND_MS)
      else -> t("duration.hours", "hours" to ms / HOUR_MS, "minutes" to (ms % HOUR_MS) / MINUTE_MS)
    }
  }
}
