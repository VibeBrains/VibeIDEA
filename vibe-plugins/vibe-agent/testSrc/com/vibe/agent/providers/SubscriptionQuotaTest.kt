// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the vendor answered, read for certain — and «no data» for anything else, never a made-up 0.
 *
 * The answers and what they must read as live in the shared set (`testVectors/subscriptionQuota.json`, VibeBrains):
 * VibeIDE reads the same file, so one vendor answer is read the same way in both products, and a fixture fixed in one
 * place is fixed for both.
 */
class SubscriptionQuotaTest {
  private val vectors: JsonObject by lazy {
    val text = javaClass.getResource(VECTORS)?.readText() ?: error("нет $VECTORS в classpath — указатель набора не поднят?")
    Json.parseToJsonElement(text).jsonObject
  }

  @Test
  fun `every shared vendor answer reads as the shared expectation`() {
    val cases = vectors["parse"]!!.jsonArray.map { it.jsonObject }
    assertTrue(cases.size >= 9, "векторов стало меньше: ${cases.size}")
    for (case in cases) {
      val name = case["name"]!!.jsonPrimitive.content
      val actual = SubscriptionQuota.parse(case["format"]!!.jsonPrimitive.content, case["body"]!!.jsonPrimitive.content)
      assertEquals(expected(case["expect"]!!.jsonObject), actual, name)
    }
  }

  @Test
  fun `every shared leftShare case agrees`() {
    for (case in vectors["leftShare"]!!.jsonArray.map { it.jsonObject }) {
      val actual = SubscriptionQuota.leftShare(
        case.num("count"), case.num("total")!!, case.num("remainingPercent"))
      val expect = case.num("expect")
      if (expect == null) assertNull(actual, case.toString()) else assertEquals(expect, actual!!, 1e-9, case.toString())
    }
  }

  private fun expected(o: JsonObject): SubscriptionQuota.Result = when (val kind = o["kind"]!!.jsonPrimitive.content) {
    "windows" -> SubscriptionQuota.Result.Windows(o["windows"]!!.jsonArray.map { w ->
      val wo = w.jsonObject
      SubscriptionQuota.Window(
        scope = (wo["scope"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull,
        windowMs = (wo["windowMs"] as? JsonPrimitive)?.longOrNull,
        leftPercent = (wo["leftPercent"] as? JsonPrimitive)?.intOrNull,
        resetAtMs = (wo["resetAtMs"] as? JsonPrimitive)?.longOrNull,
      )
    })
    "vendorError" -> SubscriptionQuota.Result.VendorError(o["message"]!!.jsonPrimitive.content)
    "unreadable" -> SubscriptionQuota.Result.Unreadable
    else -> error("незнакомый kind в векторах: $kind")
  }

  private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

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

  private companion object {
    const val VECTORS = "/vibeDefaults/testVectors/subscriptionQuota.json"
  }
}
