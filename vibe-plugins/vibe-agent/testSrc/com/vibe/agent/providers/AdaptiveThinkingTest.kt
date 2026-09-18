// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Два несовместимых написания мышления на проводе Anthropic.
 *
 * `thinking: {"type":"enabled","budget_tokens":N}` возвращает 400 на Opus 4.7, 4.8 и всей линейке 5;
 * `{"type":"adaptive"}` возвращает 400 на 4.5 и более ранних. Ошибаются ОБА направления, поэтому
 * написание — свойство модели, а не наше умолчание
 * (platform.claude.com/docs/en/build-with-claude/extended-thinking и /effort, сверено 18.09.2026).
 */
class AdaptiveThinkingTest {
  private val high = ReasoningMode.Level.HIGH

  @Test
  fun `the five line and opus 4-7 and 4-8 are marked adaptive`() {
    for (id in listOf("claude-opus-5", "claude-sonnet-5", "claude-fable-5-1", "claude-mythos-5",
                      "claude-opus-4-7", "claude-opus-4-8")) {
      assertTrue(ModelQuirks.has(id, ModelQuirks.Quirk.ADAPTIVE_THINKING), id)
    }
  }

  @Test
  fun `4-5 and 4-6 keep the token budget`() {
    // На них адаптивного режима нет вовсе, и он тоже отвечает 400 — значит правило обязано
    // остановиться ровно на границе поколений.
    for (id in listOf("claude-sonnet-4-5", "claude-opus-4-5-20251101", "claude-haiku-4-5", "claude-opus-4-6")) {
      assertTrue(!ModelQuirks.has(id, ModelQuirks.Quirk.ADAPTIVE_THINKING), id)
    }
  }

  @Test
  fun `an adaptive model gets the mode and the effort, and no budget at all`() {
    val body = ReasoningMode.bodyFields("anthropic", high, maxOutputTokens = 64_000, adaptive = true)
    assertEquals("adaptive", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    assertNull(body["thinking"]!!.jsonObject["budget_tokens"])
    assertEquals("high", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
  }

  @Test
  fun `an adaptive model speaks the vendor's own words when the entry declares them`() {
    val support = ReasoningMode.Support(words = listOf("low", "medium", "high", "xhigh", "max"))
    val body = ReasoningMode.bodyFields("anthropic", high, 64_000, support, adaptive = true)
    // Верхнее положение ползунка — верхнее слово вендора, как и на прочих проводах.
    assertEquals("max", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
  }

  @Test
  fun `a model without the quirk still gets the budget, unchanged`() {
    val body = ReasoningMode.bodyFields("anthropic", high, maxOutputTokens = 64_000)
    assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    assertNull(body["output_config"])
  }

  @Test
  fun `off sends the declared switch and never an effort`() {
    val support = ReasoningMode.Support(off = kotlinx.serialization.json.buildJsonObject {
      put("thinking", kotlinx.serialization.json.buildJsonObject { put("type", kotlinx.serialization.json.JsonPrimitive("disabled")) })
    })
    val body = ReasoningMode.bodyFields("anthropic", ReasoningMode.Level.OFF, 64_000, support, adaptive = true)
    assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    assertNull(body["output_config"])
  }
}
