// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptCacheTest {
  private fun user(text: String) = ChatMessage("user", text)
  private fun assistant(text: String) = ChatMessage("assistant", text)

  @Test
  fun `a short preamble is not worth caching`() {
    // Запись в кэш сама по себе не бесплатна: кэшировать две строки — это убыток.
    assertFalse(PromptCache.shouldCacheSystem("коротко"))
    assertTrue(PromptCache.shouldCacheSystem("x".repeat(PromptCache.MIN_CACHEABLE_CHARS)))
  }

  @Test
  fun `the boundary ends before the message that changed`() {
    // Включить последнее сообщение — получить запись кэша, использованную ровно один раз.
    val messages = listOf(user("x".repeat(3000)), assistant("ответ"), user("новый вопрос"))
    assertEquals(1, PromptCache.cacheBoundary(messages))
  }

  @Test
  fun `a short conversation has nothing stable to cache`() {
    assertNull(PromptCache.cacheBoundary(listOf(user("вопрос"))))
    assertNull(PromptCache.cacheBoundary(emptyList()))
  }

  @Test
  fun `a small prefix is not cached even in a long conversation`() {
    val messages = listOf(user("раз"), assistant("два"), user("три"))
    assertNull(PromptCache.cacheBoundary(messages))
  }

  @Test
  fun `the threshold is measured over the prefix, not the whole conversation`() {
    val messages = listOf(user("коротко"), assistant("тоже"), user("x".repeat(10_000)))
    assertNull(PromptCache.cacheBoundary(messages), "длинным был только последний ход")
  }
}
