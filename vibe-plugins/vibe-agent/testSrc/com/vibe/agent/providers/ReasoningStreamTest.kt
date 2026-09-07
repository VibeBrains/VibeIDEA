// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Рассуждение в потоке: молчащая модель выглядит зависшей, а мысль в ответе — ломает ответ. */
class ReasoningStreamTest {
  private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

  @Test
  fun `thinking_delta у Anthropic — это мысль`() {
    assertEquals("прикидываю", ReasoningStream.fromAnthropicEvent(obj("""
      { "type": "content_block_delta", "delta": { "type": "thinking_delta", "thinking": "прикидываю" } }
    """.trimIndent())))
  }

  @Test
  fun `совместимый эндпоинт без типа дельты не теряет рассуждение`() {
    assertEquals("думаю", ReasoningStream.fromAnthropicEvent(obj("""
      { "type": "content_block_delta", "delta": { "thinking": "думаю" } }
    """.trimIndent())))
  }

  @Test
  fun `обычный текст мыслью не считается`() {
    assertNull(ReasoningStream.fromAnthropicEvent(obj("""
      { "type": "content_block_delta", "delta": { "type": "text_delta", "text": "ответ" } }
    """.trimIndent())))
    assertNull(ReasoningStream.fromAnthropicEvent(obj("""{ "type": "message_start" }""")))
  }

  @Test
  fun `reasoning_content и reasoning у OpenAI-совместимых`() {
    assertEquals("шаг", ReasoningStream.fromOpenAiChunk(obj("""
      { "choices": [ { "delta": { "reasoning_content": "шаг" } } ] }
    """.trimIndent())))
    assertEquals("шаг", ReasoningStream.fromOpenAiChunk(obj("""
      { "choices": [ { "delta": { "reasoning": "шаг" } } ] }
    """.trimIndent())))
    assertNull(ReasoningStream.fromOpenAiChunk(obj("""
      { "choices": [ { "delta": { "content": "ответ" } } ] }
    """.trimIndent())))
  }

  @Test
  fun `у Gemini мысль и ответ различаются пометкой, а не порядком`() {
    val event = obj("""
      { "candidates": [ { "content": { "parts": [
        { "text": "размышление", "thought": true },
        { "text": "ответ" }
      ] } } ] }
    """.trimIndent())
    assertEquals("размышление", ReasoningStream.fromGeminiEvent(event))
    // Раньше бралась ПЕРВАЯ часть — при включённых рассуждениях мысль уезжала в ответ.
    assertEquals("ответ", ReasoningStream.answerFromGeminiEvent(event))
  }

  @Test
  fun `ответ без рассуждений читается как раньше`() {
    val event = obj("""{ "candidates": [ { "content": { "parts": [ { "text": "просто ответ" } ] } } ] }""")
    assertNull(ReasoningStream.fromGeminiEvent(event))
    assertEquals("просто ответ", ReasoningStream.answerFromGeminiEvent(event))
  }
}
