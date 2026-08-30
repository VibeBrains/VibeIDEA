// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReasoningModeTest {
  @Test
  fun `the level is read in both languages and defaults to off`() {
    assertEquals(ReasoningMode.Level.LOW, ReasoningMode.levelOf("низкий"))
    assertEquals(ReasoningMode.Level.HIGH, ReasoningMode.levelOf("HIGH"))
    assertEquals(ReasoningMode.Level.OFF, ReasoningMode.levelOf(null))
    assertEquals(ReasoningMode.Level.OFF, ReasoningMode.levelOf("что-то"))
  }

  @Test
  fun `off adds nothing to any provider`() {
    for (protocol in listOf("anthropic", "gemini", "openai")) {
      assertTrue(ReasoningMode.bodyFields(protocol, ReasoningMode.Level.OFF, 8000).isEmpty())
    }
  }

  @Test
  fun `each provider is spoken to in its own dialect`() {
    // reasoning_effort, отправленный Anthropic, не делает НИЧЕГО и молча: человек решает,
    // что ползунок декоративный.
    val anthropic = ReasoningMode.bodyFields("anthropic", ReasoningMode.Level.MEDIUM, 32_000)
    assertEquals("enabled", anthropic["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    val gemini = ReasoningMode.bodyFields("gemini", ReasoningMode.Level.MEDIUM, 32_000)
    assertTrue(gemini.containsKey("generationConfig"))
    val openai = ReasoningMode.bodyFields("openai", ReasoningMode.Level.MEDIUM, 32_000)
    assertEquals("medium", openai["reasoning_effort"]!!.jsonPrimitive.content)
  }

  @Test
  fun `the thinking budget leaves room for the answer`() {
    // Бюджет, равный max_tokens, API отклоняет, и выглядит это как «модель молчит».
    val body = ReasoningMode.bodyFields("anthropic", ReasoningMode.Level.HIGH, 8_000)
    val budget = body["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content.toInt()
    assertTrue(budget <= 8_000 - ReasoningMode.MIN_ANSWER_TOKENS, "бюджет $budget не оставляет места ответу")
  }

  @Test
  fun `an unknown max_tokens still yields a usable budget`() {
    val body = ReasoningMode.bodyFields("anthropic", ReasoningMode.Level.HIGH, null)
    val budget = body["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content.toInt()
    assertTrue(budget >= 1_024)
  }

  @Test
  fun `deeper levels ask for more`() {
    assertTrue(ReasoningMode.budgetTokens(ReasoningMode.Level.LOW)!! <
                 ReasoningMode.budgetTokens(ReasoningMode.Level.MEDIUM)!!)
    assertTrue(ReasoningMode.budgetTokens(ReasoningMode.Level.MEDIUM)!! <
                 ReasoningMode.budgetTokens(ReasoningMode.Level.HIGH)!!)
  }
}
