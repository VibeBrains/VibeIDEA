// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Окно, заявленное провайдером: три источника про MiniMax-M3 назвали три разных числа, и ни одно
 * не совпало с правдой. Расхождение не чинится молча — оно называется.
 */
class ClaimedContextTest {
  private fun root(text: String) = Json.parseToJsonElement(text).jsonObject

  @Test
  fun `имя поля у каждого вендора своё`() {
    assertEquals(
      mapOf("a" to 200000L, "b" to 1000000L, "c" to 128000L),
      ClaimedContext.parse(root("""
        { "data": [
          { "id": "a", "context_window": 200000 },
          { "id": "b", "context_length": 1000000 },
          { "id": "c", "max_input_tokens": 128000 }
        ] }
      """.trimIndent())),
    )
  }

  @Test
  fun `Gemini называет окно по-своему и в списке models`() {
    assertEquals(
      mapOf("gemini-3.1-pro" to 1048576L),
      ClaimedContext.parse(root("""
        { "models": [ { "name": "models/gemini-3.1-pro", "inputTokenLimit": 1048576 } ] }
      """.trimIndent())),
    )
  }

  @Test
  fun `OpenRouter прячет настоящее окно внутри top_provider`() {
    assertEquals(
      mapOf("minimax/minimax-m3" to 1000000L),
      ClaimedContext.parse(root("""
        { "data": [ { "id": "minimax/minimax-m3", "top_provider": { "context_length": 1000000 } } ] }
      """.trimIndent())),
    )
  }

  @Test
  fun `молчание провайдера — не ноль`() {
    assertNull(ClaimedContext.of(root("""{ "id": "x" }""")))
    assertNull(ClaimedContext.of(root("""{ "id": "x", "context_length": 0 }""")))
    assertEquals(emptyMap(), ClaimedContext.parse(root("""{ "object": "list" }""")))
  }

  @Test
  fun `округление вендора расхождением не считается`() {
    assertEquals(ClaimedContext.Verdict.AGREE, ClaimedContext.compare(128000, 131072))
  }

  @Test
  fun `случай MiniMax разбирается на две разные стороны`() {
    // Конфиг человека честнее: провайдер занижает вдвое (200K против 1M) — модель недоиспользуется.
    assertEquals(ClaimedContext.Verdict.CONFIG_LARGER, ClaimedContext.compare(1_000_000, 200_000))
    // Обратный случай опаснее иначе: разговор упрётся в отказ раньше, чем ждёт человек.
    assertEquals(ClaimedContext.Verdict.CONFIG_SMALLER, ClaimedContext.compare(200_000, 1_000_000))
    assertEquals(ClaimedContext.Verdict.UNKNOWN, ClaimedContext.compare(null, 1_000_000))
    assertEquals(ClaimedContext.Verdict.UNKNOWN, ClaimedContext.compare(200_000, null))
  }

  @Test
  fun `занижение провайдера называется первым`() {
    val providers = listOf(ProviderEntry(id = "p", models = listOf(
      ModelEntry(id = "мало-в-конфиге", contextWindow = 200_000),
      ModelEntry(id = "много-в-конфиге", contextWindow = 1_000_000),
      ModelEntry(id = "сошлось", contextWindow = 128_000),
    )))
    val claims = mapOf("p" to mapOf(
      "мало-в-конфиге" to 1_000_000L,
      "много-в-конфиге" to 200_000L,
      "сошлось" to 131_072L,
    ))
    val notices = ClaimedContext.notices(providers, claims)
    assertEquals(listOf("много-в-конфиге", "мало-в-конфиге"), notices.map { it.modelId })
  }
}
