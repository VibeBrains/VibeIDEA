// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What a subscription has left, as the VENDOR reports it — never computed by us.
 *
 * A plan's credits are recounted by the vendor's own rules (multipliers per model, peak hours), and usage made outside
 * the IDE with the same key is invisible here; a counter of ours would be a guess dressed as a number. So the IDE asks
 * the vendor's endpoint named in the provider's `quota` field and shows its answer, and anything it cannot read for
 * certain is «no data», never 0.
 *
 * Pure: a response body in, windows out. The request is [SubscriptionQuotaFetch].
 */
object SubscriptionQuota {
  /**
   * MiniMax Token Plan, `GET …/v1/token_plan/remains` (platform.minimax.io/docs/token-plan/faq). Field meanings follow
   * the vendor's own CLI (github.com/MiniMax-AI/cli: `src/types/api.ts`, `src/utils/quota.ts`, checked 17.09.2026).
   */
  const val MINIMAX_TOKEN_PLAN = "minimax-token-plan"

  /**
   * Z.ai GLM Coding Plan, `GET https://api.z.ai/api/monitor/usage/quota/limit`. NOT documented by the vendor: the shape
   * is the one read by CodexBar, openclaw and onWatch, with the raw `CREDIT_LIMIT` answer of 02.09.2026 in onWatch#122 —
   * and it has already changed once without notice.
   */
  const val ZAI_MONITOR = "zai-monitor"

  val FORMATS: Set<String> = setOf(MINIMAX_TOKEN_PLAN, ZAI_MONITOR)

  /**
   * One window of the plan.
   *
   * @param scope what the window limits, as the vendor names it (a model family, `MCP`); null — the plan as a whole.
   * @param windowMs the window's length; null when the vendor's unit is not one we know.
   * @param leftPercent what is left; above 100 when the vendor grants a boost; null — unlimited.
   * @param resetAtMs when the window resets, epoch milliseconds; null when not reported.
   */
  data class Window(val scope: String?, val windowMs: Long?, val leftPercent: Int?, val resetAtMs: Long?)

  sealed interface Result {
    data class Windows(val windows: List<Window>) : Result
    /** The vendor answered with an error of its own; [message] is its text. */
    data class VendorError(val message: String) : Result
    /** The answer could not be read for certain — an unknown shape, or nothing in it. */
    data object Unreadable : Result
  }

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  fun parse(format: String, body: String): Result {
    val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return Result.Unreadable
    return when (format) {
      MINIMAX_TOKEN_PLAN -> minimax(root)
      ZAI_MONITOR -> zai(root)
      else -> Result.Unreadable
    }
  }

  // --- MiniMax ---

  /** `*_status` of the vendor: 3 — unlimited (and, with zero totals on both windows, not in the plan at all). */
  private const val MINIMAX_UNLIMITED = 3

  /** A count and a percentage that disagree by more than this are not trusted either way (the vendor CLI's tolerance). */
  private const val PERCENT_TOLERANCE = 1.0

  private fun minimax(root: JsonObject): Result {
    val base = root["base_resp"] as? JsonObject
    val code = base?.long("status_code")
    if (code != null && code != 0L) return Result.VendorError(base.text("status_msg") ?: "status_code $code")
    val models = root["model_remains"] as? JsonArray ?: return Result.Unreadable
    val windows = models.mapNotNull { it as? JsonObject }.flatMap { m ->
      val scope = m.text("model_name")
      val intervalTotal = m.number("current_interval_total_count")
      val weeklyTotal = m.number("current_weekly_total_count")
      val intervalStatus = m.long("current_interval_status")
      val weeklyStatus = m.long("current_weekly_status")
      // Both windows «unlimited» with nothing in them is how the API marks a model outside the plan (vendor CLI #173).
      if (intervalTotal == 0.0 && weeklyTotal == 0.0 && intervalStatus == MINIMAX_UNLIMITED.toLong() &&
          weeklyStatus == MINIMAX_UNLIMITED.toLong()) return@flatMap emptyList()
      listOfNotNull(
        minimaxWindow(scope, m, "start_time", "end_time", intervalTotal, "current_interval_usage_count",
                      "current_interval_remaining_percent", intervalStatus, boost = 1.0),
        minimaxWindow(scope, m, "weekly_start_time", "weekly_end_time", weeklyTotal, "current_weekly_usage_count",
                      "current_weekly_remaining_percent", weeklyStatus,
                      boost = (m.number("weekly_boost_permille") ?: 1000.0).coerceAtLeast(0.0) / 1000.0),
      )
    }
    return if (windows.isEmpty()) Result.Unreadable else Result.Windows(windows)
  }

  private fun minimaxWindow(
    scope: String?, m: JsonObject, startKey: String, endKey: String, total: Double?, countKey: String,
    percentKey: String, status: Long?, boost: Double,
  ): Window? {
    val start = m.long(startKey)
    val end = m.long(endKey)
    val span = if (start != null && end != null && end > start) end - start else null
    if (status == MINIMAX_UNLIMITED.toLong()) return Window(scope, span, leftPercent = null, resetAtMs = end)
    // A window with no total is not part of this plan (the weekly one of a text model, in the vendor's own fixture).
    if (total == null || total <= 0.0) return null
    val left = leftShare(m.number(countKey), total, m.number(percentKey)) ?: return null
    return Window(scope, span, Math.round(left * 100.0 * boost).toInt(), end)
  }

  /**
   * The share left, from `*_usage_count`, whose meaning the vendor itself changed: older answers put what is LEFT in
   * it, newer ones may put what is USED. With the vendor's remaining percentage beside it, the reading that agrees with
   * the percentage wins; if neither agrees, nothing is shown. Without a percentage the older reading holds — as in the
   * vendor's CLI (`resolveQuotaCounts`), which is the only authority on this field there is.
   */
  internal fun leftShare(count: Double?, total: Double, remainingPercent: Double?): Double? {
    if (count == null || count < 0 || count > total || total <= 0) return null
    val asLeft = count / total * 100.0
    if (remainingPercent == null) return count / total
    val asUsed = (total - count) / total * 100.0
    val leftDistance = Math.abs(asLeft - remainingPercent)
    val usedDistance = Math.abs(asUsed - remainingPercent)
    if (minOf(leftDistance, usedDistance) > PERCENT_TOLERANCE) return null
    return if (usedDistance < leftDistance) (total - count) / total else count / total
  }

  // --- Z.ai ---

  private const val ZAI_OK = 200L

  /** Limit types that carry the plan's windows: tokens before September 2026, credits since (onWatch#122). */
  private val ZAI_PLAN_TYPES = setOf("TOKENS_LIMIT", "CREDIT_LIMIT")

  /** The MCP tools' monthly allowance — a window of its own, never the plan's. */
  private const val ZAI_MCP_TYPE = "TIME_LIMIT"
  const val ZAI_MCP_SCOPE = "MCP"

  private const val MINUTE_MS = 60_000L
  private const val HOUR_MS = 60 * MINUTE_MS
  private const val DAY_MS = 24 * HOUR_MS

  /**
   * `unit` of a limit → its length. 5, 3 and 1 are read so by CodexBar and openclaw; 6 as a week is inferred from the
   * raw answer in onWatch#122 (`unit 6, number 1` beside a five-hour `unit 3, number 5`). Anything else — unknown length.
   */
  private val ZAI_UNIT_MS: Map<Long, Long> = mapOf(5L to MINUTE_MS, 3L to HOUR_MS, 1L to DAY_MS, 6L to 7 * DAY_MS)

  private fun zai(root: JsonObject): Result {
    val success = (root["success"] as? JsonPrimitive)?.booleanOrNull
    val code = root.long("code")
    if (success != true || code != ZAI_OK) {
      return if (success == null && code == null) Result.Unreadable else Result.VendorError(root.text("msg") ?: "code $code")
    }
    val limits = (root["data"] as? JsonObject)?.get("limits") as? JsonArray ?: return Result.Unreadable
    val windows = limits.mapNotNull { it as? JsonObject }.mapNotNull { limit ->
      val type = limit.text("type")
      val scope = when (type) {
        in ZAI_PLAN_TYPES -> null
        ZAI_MCP_TYPE -> ZAI_MCP_SCOPE
        else -> return@mapNotNull null
      }
      // The percentage is USED, an integer; without it the limit says nothing we may show.
      val used = limit.number("percentage")?.takeIf { it >= 0 } ?: return@mapNotNull null
      val unit = limit.long("unit")?.let { ZAI_UNIT_MS[it] }
      val span = unit?.let { u -> limit.long("number")?.takeIf { it > 0 }?.let { it * u } }
      Window(scope, span, (100 - Math.round(used).toInt()).coerceIn(0, 100), limit.long("nextResetTime"))
    }
    return if (windows.isEmpty()) Result.Unreadable else Result.Windows(windows)
  }

  // --- reading ---

  private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
  private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
  private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
}
