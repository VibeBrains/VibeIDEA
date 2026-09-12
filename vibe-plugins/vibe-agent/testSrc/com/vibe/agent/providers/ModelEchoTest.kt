// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Кто ответил на самом деле: чтение имени модели с каждого провода и сравнение с запрошенной. */
class ModelEchoTest {
  private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

  @Test
  fun `each wire names the model its own way`() {
    assertEquals("gpt-4o-2024-08-06", ModelEcho.fromOpenAiChunk(obj("""{"model":"gpt-4o-2024-08-06","choices":[]}""")))
    assertEquals("claude-opus-5", ModelEcho.fromAnthropicEvent(obj("""{"type":"message_start","message":{"model":"claude-opus-5"}}""")))
    assertEquals("gemini-3.8-flash", ModelEcho.fromGeminiEvent(obj("""{"modelVersion":"gemini-3.8-flash","candidates":[]}""")))
  }

  @Test
  fun `an event that says nothing about the model says nothing`() {
    assertNull(ModelEcho.fromOpenAiChunk(obj("""{"choices":[]}""")))
    assertNull(ModelEcho.fromAnthropicEvent(obj("""{"type":"content_block_delta"}""")))
    assertNull(ModelEcho.fromGeminiEvent(obj("""{"candidates":[]}""")))
    assertNull(ModelEcho.fromOpenAiChunk(obj("""{"model":"  "}""")))
  }

  @Test
  fun `the same model spelled differently is not a substitution`() {
    // Псевдоним, разрешённый в датированную сборку, и пространство имён агрегатора.
    assertFalse(ModelEcho.substituted("gpt-4o", "gpt-4o-2024-08-06"))
    assertFalse(ModelEcho.substituted("openai/gpt-4o", "gpt-4o"))
    assertFalse(ModelEcho.substituted("gpt-4o", "openai/gpt-4o"))
    assertFalse(ModelEcho.substituted("claude-opus-5", "Claude-Opus-5"))
    assertFalse(ModelEcho.substituted("qwen3-max", "qwen3-max@2026-01-01"))
    // Хвост-сборка может начинаться с цифр и продолжаться словом.
    assertFalse(ModelEcho.substituted("kimi-k2", "kimi-k2-0905-preview"))
    assertFalse(ModelEcho.substituted("gemini-3.8-flash", "gemini-3.8-flash-002"))
  }

  @Test
  fun `another model is a substitution`() {
    assertTrue(ModelEcho.substituted("claude-opus-5", "claude-haiku-4-5"))
    // Хвост-слово — другая модель, и прятать это за «переименованием» нельзя.
    assertTrue(ModelEcho.substituted("gpt-4o", "gpt-4o-mini"))
    assertTrue(ModelEcho.substituted("gpt-4o", "gpt-4o-mini-2024-07-18"))
    assertTrue(ModelEcho.substituted("openai/gpt-4o", "anthropic/claude-opus-5"))
  }

  @Test
  fun `silence is not an accusation`() {
    // Провод, который своё имя не называет, не повод пугать владельца строкой о подмене.
    assertFalse(ModelEcho.substituted("gpt-4o", null))
    assertFalse(ModelEcho.substituted("gpt-4o", "   "))
    assertFalse(ModelEcho.substituted("", "gpt-4o"))
  }
}
