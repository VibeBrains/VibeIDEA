// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A refusal's credit: read from the stream, held for one retry on the same provider, dropped when the vendor refuses it */
class FallbackCreditTest {
  @Test
  fun `the token is read from the refusal's stop details`() {
    val event = Json.parseToJsonElement("""{"type":"message_delta","delta":{"stop_reason":"refusal",
      "stop_details":{"type":"refusal","category":"cyber","explanation":"x","fallback_credit_token":"tok-1",
      "fallback_has_prefill_claim":false}}}""").jsonObject
    val stop = StopReason.fromAnthropicEvent(event)!!
    assertEquals(StopReason.Kind.REFUSAL, stop.kind)
    assertEquals("tok-1", stop.creditToken)
  }

  @Test
  fun `the beta goes only to anthropic's own api`() {
    assertTrue(FallbackCredit.offered("https://api.anthropic.com/v1"))
    assertFalse(FallbackCredit.offered("https://api.minimax.io/anthropic/v1"))
    assertFalse(FallbackCredit.offered("not a url"))
  }

  @Test
  fun `the token serves the same provider within five minutes`() {
    val credit = FallbackCredit.Credit("anthropic", "tok", 1_000)
    assertEquals("tok", FallbackCredit.usable(credit, "anthropic", 1_000 + FallbackCredit.TTL_MS - 1))
    assertNull(FallbackCredit.usable(credit, "anthropic", 1_000 + FallbackCredit.TTL_MS))
    assertNull(FallbackCredit.usable(credit, "openrouter", 2_000))
    assertNull(FallbackCredit.usable(null, "anthropic", 2_000))
  }

  @Test
  fun `a refused token is dropped, a transient refusal repeats, anything else stands`() {
    assertEquals(FallbackCredit.OnError.DROP_TOKEN, FallbackCredit.onError("HTTP 400: invalid fallback_credit_token"))
    assertEquals(FallbackCredit.OnError.DROP_TOKEN, FallbackCredit.onError("HTTP 400: request body does not match the refused request"))
    assertEquals(FallbackCredit.OnError.REPEAT, FallbackCredit.onError("HTTP 400: redemption temporarily unavailable"))
    assertEquals(FallbackCredit.OnError.RAISE, FallbackCredit.onError("HTTP 400: max_tokens too large"))
    assertEquals(FallbackCredit.OnError.RAISE, FallbackCredit.onError("HTTP 529: overloaded, fallback_credit_token"))
  }
}
