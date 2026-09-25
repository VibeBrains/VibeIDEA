// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A model with the hour-long cache pays the hour rate for every cache write
 * While one rate served both lifetimes, an hour write of Opus 5.5 was billed at $5 instead of $8
 * The rule is VibeIDE's `withHourCacheWrite`: the declared hour rate, twice the input without it
 */
class HourCacheWriteTest {
  // Opus 5.5 as the shared set writes it down (providers/anthropic.jsonc)
  private val opus = ModelPricing(input = 4.0, output = 20.0, cacheRead = 0.2, cacheWrite = 5.0, cacheWrite1h = 8.0)
  private val written = TokenUsage(cacheWriteTokens = 1_000_000)
  private val today = LocalDate.of(2026, 9, 25)

  @Test
  fun `an hour write is billed at the hour rate`() {
    assertEquals(8.0, opus.forCacheTtl("1h").costOf(written, null)!!, 1e-9)
  }

  @Test
  fun `without the hour rate an hour write costs twice the input, not the five-minute rate`() {
    assertEquals(8.0, opus.copy(cacheWrite1h = 0.0).forCacheTtl("1h").costOf(written, null)!!, 1e-9)
  }

  @Test
  fun `the five-minute cache keeps its own rate`() {
    for (ttl in listOf(null, "", "5m")) {
      assertEquals(5.0, opus.forCacheTtl(ttl).costOf(written, null)!!, 1e-9, "ttl=$ttl")
    }
  }

  @Test
  fun `the price in force carries the lifetime of the model's cache`() {
    val model = ModelEntry(id = "claude-opus-5-5", pricing = opus, cacheTtl = "1h")
    assertEquals(8.0, PriceValidity.effective(model, today)!!.cacheWrite, 1e-9)
    assertEquals(5.0, PriceValidity.effective(model.copy(cacheTtl = null), today)!!.cacheWrite, 1e-9)
    // Past its date the future price is the one in force, and it is billed for the same lifetime
    val expired = model.copy(priceValidUntil = "2026-09-01", priceAfter = opus.copy(input = 5.0, cacheWrite1h = 0.0))
    assertEquals(10.0, PriceValidity.effective(expired, today)!!.cacheWrite, 1e-9)
  }

  @Test
  fun `the pay-off of an hour write is counted by the hour rate`() {
    val model = ModelEntry(id = "claude-opus-5-5", pricing = opus, cacheTtl = "1h")
    // The write costs 4 above the input, a hit saves 3.8: two hits pay for it, so from the third request
    assertEquals(3, CacheWindow.paysOffFromRequest(PriceValidity.effective(model, today), "1h"))
    // The five-minute write costs 1 above the input and pays off with the first hit
    assertEquals(2, CacheWindow.paysOffFromRequest(PriceValidity.effective(model.copy(cacheTtl = null), today), null))
  }

  @Test
  fun `the hour rate is read from cost and from costAfter`() {
    val text = """
      { "providers": [ { "id": "anthropic", "models": { "static": [
        { "id": "claude-opus-5-5", "cacheTtl": "1h",
          "cost": { "input": 4, "output": 20, "cacheWrite": 5, "cacheWrite1h": 8 },
          "costAfter": { "input": 5, "cacheWrite1h": 10 } }
      ] } } ] }
    """.trimIndent()
    val model = ProvidersFile.parse(text) { }.single().models.single()
    assertEquals(8.0, model.pricing!!.cacheWrite1h, 1e-9)
    assertEquals(10.0, model.priceAfter!!.cacheWrite1h, 1e-9)
  }

  @Test
  fun `a price naming only the hour rate is a stated price`() {
    assertTrue(ModelPricing(cacheWrite1h = 8.0).stated)
  }
}
