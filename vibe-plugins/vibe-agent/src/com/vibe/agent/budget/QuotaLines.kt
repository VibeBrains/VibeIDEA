// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.budget

import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.providers.SubscriptionQuota
import com.vibe.agent.providers.SubscriptionQuotaFetch

/**
 * The «what the subscription has left» section of the spending report — one wording for `/spend` and the menu report.
 * Every line says it is the vendor's answer and when it was asked; what could not be read says so instead of a number.
 */
object QuotaLines {
  private const val MINUTE_MS = 60_000L
  private const val HOUR_MS = 60 * MINUTE_MS
  private const val DAY_MS = 24 * HOUR_MS
  private const val WEEK_MS = 7 * DAY_MS
  private val RESET_FORMAT: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm")

  /** Empty when no provider declares a `quota` with a key: the section is not there at all. */
  fun render(rows: List<SubscriptionQuotaFetch.Row>, askedAtMs: Long): List<String> {
    if (rows.isEmpty()) return emptyList()
    return buildList {
      add(t("spend.quota.title", "time" to time(askedAtMs)))
      for (row in rows) {
        add("  " + t("spend.quota.provider", "provider" to row.provider.name))
        if (row.spec.format == SubscriptionQuota.ZAI_MONITOR) add("    " + t("spend.quota.undocumented"))
        when (val outcome = row.outcome) {
          is SubscriptionQuotaFetch.Outcome.Failed -> add("    " + t("spend.quota.failed", "reason" to outcome.reason))
          is SubscriptionQuotaFetch.Outcome.Answered -> when (val result = outcome.result) {
            is SubscriptionQuota.Result.VendorError -> add("    " + t("spend.quota.vendorError", "message" to result.message))
            SubscriptionQuota.Result.Unreadable -> add("    " + t("spend.quota.unreadable"))
            is SubscriptionQuota.Result.Windows -> result.windows.forEach { add("    " + window(it)) }
          }
        }
      }
    }
  }

  private fun window(w: SubscriptionQuota.Window): String {
    val name = listOfNotNull(w.scope, w.windowMs?.let { span(it) }).joinToString(", ").ifEmpty { t("spend.quota.plan") }
    val left = w.leftPercent?.let { t("spend.quota.left", "percent" to it) } ?: t("spend.quota.unlimited")
    val reset = w.resetAtMs?.let { " " + t("spend.quota.reset", "time" to time(it)) }.orEmpty()
    return "$name: $left$reset"
  }

  internal fun span(ms: Long): String = when {
    ms % WEEK_MS == 0L -> t("spend.quota.span.weeks", "count" to ms / WEEK_MS)
    ms % DAY_MS == 0L -> t("spend.quota.span.days", "count" to ms / DAY_MS)
    ms >= HOUR_MS -> t("spend.quota.span.hours", "count" to Math.round(ms.toDouble() / HOUR_MS))
    else -> t("spend.quota.span.minutes", "count" to maxOf(1L, Math.round(ms.toDouble() / MINUTE_MS)))
  }

  private fun time(ms: Long): String =
    java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).format(RESET_FORMAT)
}
