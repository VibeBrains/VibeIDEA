// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Часовой кэш: дороже в записи, поэтому включается осознанно и только правильным написанием. */
class PromptCacheTtlTest {
  @Test
  fun `часовой срок узнаётся, пятиминутный не пишется вовсе`() {
    assertEquals(PromptCache.TTL_1H, PromptCache.ttlOf("1h"))
    assertEquals(PromptCache.TTL_1H, PromptCache.ttlOf(" 1H "))
    assertNull(PromptCache.ttlOf("5m"), "умолчание вендора не нужно писать в запрос")
    assertNull(PromptCache.ttlOf(null))
  }

  @Test
  fun `чужое написание не пропускается`() {
    // «1 hour» в конфиге дало бы отказ вендора на каждом запросе; неизвестное честнее считать
    // несказанным и остаться на умолчании.
    assertNull(PromptCache.ttlOf("1 hour"))
    assertNull(PromptCache.ttlOf("60m"))
  }

  @Test
  fun `бета-заголовок нужен только часовому`() {
    assertTrue(PromptCache.needsExtendedBeta("1h"))
    assertFalse(PromptCache.needsExtendedBeta("5m"))
    assertFalse(PromptCache.needsExtendedBeta(null))
    assertEquals("extended-cache-ttl-2025-04-11", PromptCache.EXTENDED_TTL_BETA)
  }

  @Test
  fun `маркер несёт ttl только когда он задан`() {
    assertEquals("ephemeral", LlmMessages.cacheControl(null)["type"]?.jsonPrimitive?.content)
    assertNull(LlmMessages.cacheControl(null)["ttl"])
    assertEquals("1h", LlmMessages.cacheControl("1h")["ttl"]?.jsonPrimitive?.content)
  }

  @Test
  fun `сообщение с маркером и сроком собирается целиком`() {
    val message = LlmMessages.anthropic(ChatMessage("user", "текст"), cacheable = true, ttl = "1h")
    assertTrue(message.toString().contains("\"ttl\":\"1h\""), message.toString())
    val plain = LlmMessages.anthropic(ChatMessage("user", "текст"), cacheable = true)
    assertFalse(plain.toString().contains("ttl"), plain.toString())
  }
}
