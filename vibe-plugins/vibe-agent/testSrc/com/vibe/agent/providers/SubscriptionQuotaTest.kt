// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the vendor answered, read for certain — and «no data» for anything else, never a made-up 0. */
class SubscriptionQuotaTest {
  private fun windows(format: String, body: String): List<SubscriptionQuota.Window> =
    assertIs<SubscriptionQuota.Result.Windows>(SubscriptionQuota.parse(format, body)).windows

  // The vendor CLI's own fixture (github.com/MiniMax-AI/cli, test/fixtures/quota-response.json), trimmed.
  private val minimaxFixture = """
    {"base_resp":{"status_code":0,"status_msg":"success"},"model_remains":[
      {"model_name":"MiniMax-M*","start_time":1776355200000,"end_time":1776373200000,"remains_time":7151954,
       "current_interval_total_count":1500,"current_interval_usage_count":228,
       "current_weekly_total_count":0,"current_weekly_usage_count":0,
       "weekly_start_time":1776009600000,"weekly_end_time":1776614400000,"weekly_remains_time":248351954},
      {"model_name":"speech-hd","start_time":1776355200000,"end_time":1776441600000,"remains_time":75551954,
       "current_interval_total_count":9000,"current_interval_usage_count":9000,
       "current_weekly_total_count":63000,"current_weekly_usage_count":63000,
       "weekly_start_time":1776009600000,"weekly_end_time":1776614400000,"weekly_remains_time":248351954}
    ]}"""

  @Test
  fun `MiniMax without a percentage keeps the vendor's older reading, the count is what is left`() {
    val all = windows(SubscriptionQuota.MINIMAX_TOKEN_PLAN, minimaxFixture)
    val text = all.single { it.scope == "MiniMax-M*" }
    assertEquals(15, text.leftPercent, "228 of 1500 left")
    assertEquals(5 * 3_600_000L, text.windowMs)
    assertEquals(1776373200000, text.resetAtMs)
    // No weekly total for the text model: that window is not part of the plan, not an empty one.
    assertEquals(3, all.size)
    assertEquals(listOf(100, 100), all.filter { it.scope == "speech-hd" }.map { it.leftPercent })
  }

  @Test
  fun `MiniMax with a percentage picks the reading that agrees with it, and trusts neither if none does`() {
    assertEquals(0.848, SubscriptionQuota.leftShare(228.0, 1500.0, 84.8)!!, 1e-9)
    assertEquals(0.152, SubscriptionQuota.leftShare(228.0, 1500.0, 15.2)!!, 1e-9)
    assertNull(SubscriptionQuota.leftShare(228.0, 1500.0, 50.0))
    assertNull(SubscriptionQuota.leftShare(1600.0, 1500.0, null), "a count above the total is not a count")
  }

  @Test
  fun `MiniMax unlimited, boosted and out-of-plan windows`() {
    val body = """{"base_resp":{"status_code":0},"model_remains":[
      {"model_name":"MiniMax-M*","start_time":0,"end_time":18000000,"current_interval_total_count":1000,
       "current_interval_usage_count":900,"current_interval_remaining_percent":10,
       "current_weekly_total_count":7000,"current_weekly_usage_count":3500,"current_weekly_remaining_percent":50,
       "weekly_start_time":0,"weekly_end_time":604800000,"weekly_boost_permille":1500},
      {"model_name":"video","current_interval_total_count":0,"current_weekly_total_count":0,
       "current_interval_status":3,"current_weekly_status":3},
      {"model_name":"image-01","start_time":0,"end_time":86400000,"current_interval_status":3,
       "current_weekly_total_count":0}
    ]}"""
    val all = windows(SubscriptionQuota.MINIMAX_TOKEN_PLAN, body)
    assertEquals(listOf(10, 75), all.filter { it.scope == "MiniMax-M*" }.map { it.leftPercent }, "the weekly boost of 1.5")
    assertTrue(all.none { it.scope == "video" }, "a model outside the plan is not shown")
    assertEquals(null, all.single { it.scope == "image-01" }.leftPercent, "unlimited, not zero")
  }

  @Test
  fun `MiniMax error and an unknown shape`() {
    val error = SubscriptionQuota.parse(SubscriptionQuota.MINIMAX_TOKEN_PLAN,
      """{"base_resp":{"status_code":1004,"status_msg":"login fail"}}""")
    assertEquals(SubscriptionQuota.Result.VendorError("login fail"), error)
    assertEquals(SubscriptionQuota.Result.Unreadable, SubscriptionQuota.parse(SubscriptionQuota.MINIMAX_TOKEN_PLAN, """{"remains":[]}"""))
    assertEquals(SubscriptionQuota.Result.Unreadable, SubscriptionQuota.parse(SubscriptionQuota.MINIMAX_TOKEN_PLAN, "<html>"))
  }

  // The raw answer for a Lite plan, 02.09.2026 (github.com/onllm-dev/onWatch/issues/122).
  private val zaiCredits = """{"code":200,"msg":"Operation successful","data":{"limits":[
    {"type":"CREDIT_LIMIT","unit":3,"number":5,"usage":2000,"currentValue":402,"remaining":1597,"percentage":20,"nextResetTime":1788351145586},
    {"type":"CREDIT_LIMIT","unit":6,"number":1,"usage":10000,"currentValue":5207,"remaining":4792,"percentage":52,"nextResetTime":1788784466996}
  ],"level":"lite"},"success":true}"""

  @Test
  fun `Z_ai credit windows read as used percentage, five hours and a week`() {
    val all = windows(SubscriptionQuota.ZAI_MONITOR, zaiCredits)
    assertEquals(listOf(80, 48), all.map { it.leftPercent })
    assertEquals(listOf(5 * 3_600_000L, 7 * 86_400_000L), all.map { it.windowMs })
    assertEquals(1788351145586, all.first().resetAtMs)
    assertTrue(all.all { it.scope == null })
  }

  @Test
  fun `Z_ai tokens and MCP limits, an unknown type and unit, and an error`() {
    val body = """{"code":200,"success":true,"data":{"limits":[
      {"type":"TOKENS_LIMIT","unit":3,"number":5,"percentage":7},
      {"type":"TIME_LIMIT","unit":1,"number":30,"percentage":0},
      {"type":"SOMETHING_NEW","unit":3,"number":5,"percentage":99},
      {"type":"CREDIT_LIMIT","unit":42,"number":1,"percentage":10},
      {"type":"CREDIT_LIMIT","unit":3,"number":5}
    ]}}"""
    val all = windows(SubscriptionQuota.ZAI_MONITOR, body)
    assertEquals(listOf(93, 100, 90), all.map { it.leftPercent })
    assertEquals(SubscriptionQuota.ZAI_MCP_SCOPE, all[1].scope)
    assertNull(all[2].windowMs, "an unknown unit is an unknown length, not a guess")
    assertEquals(SubscriptionQuota.Result.VendorError("token expired"),
                 SubscriptionQuota.parse(SubscriptionQuota.ZAI_MONITOR, """{"code":401,"msg":"token expired","success":false}"""))
    assertEquals(SubscriptionQuota.Result.Unreadable,
                 SubscriptionQuota.parse(SubscriptionQuota.ZAI_MONITOR, """{"code":200,"success":true,"data":{"limits":[]}}"""))
  }

  @Test
  fun `the quota field is read, inherited through extends, and a bad one is dropped with a warning`() {
    val warnings = mutableListOf<String>()
    val entries = ProvidersFile.parse("""{"providers":[
      {"id":"mm","baseURL":"https://api.minimax.io/v1","quota":{"url":"https://api.minimax.io/v1/token_plan/remains","format":"minimax-token-plan"}},
      {"id":"mm-a","extends":"mm","protocol":"anthropic"},
      {"id":"plain","baseURL":"https://x/v1","quota":{"url":"http://x/quota","format":"minimax-token-plan"}},
      {"id":"odd","baseURL":"https://x/v1","quota":{"url":"https://x/quota","format":"guess"}}
    ]}""", "test") { warnings += it }
    val resolved = ProvidersFile.resolveExtends(entries) { }
    assertEquals("minimax-token-plan", resolved.first { it.id == "mm-a" }.quota?.format)
    assertNull(resolved.first { it.id == "plain" }.quota)
    assertNull(resolved.first { it.id == "odd" }.quota)
    assertEquals(2, warnings.size)
    // One subscription behind two routes is asked once.
    assertEquals(listOf("mm"), SubscriptionQuotaFetch.targets(resolved) { "sk-cp-1" }.map { it.first.id })
  }
}
