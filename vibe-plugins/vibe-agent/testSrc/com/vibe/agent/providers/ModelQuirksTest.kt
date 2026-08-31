// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelQuirksTest {
  private fun body() = buildJsonObject {
    put("model", "o3-mini")
    put("stream", true)
    put("temperature", 0.7)
    put("top_p", 0.9)
    put("max_tokens", 4096)
    put("stop", "\n\n")
  }

  @Test
  fun `модель, о которой никто не жаловался, уходит как написана`() {
    val original = body()
    assertEquals(original, ModelQuirks.applyToBody("gpt-4o", original))
    assertTrue(ModelQuirks.quirksOf("gpt-4o").isEmpty())
    assertNull(ModelQuirks.noteOf("gpt-4o"))
  }

  @Test
  fun `рассуждающая модель не принимает сэмплирование и stop`() {
    val fixed = ModelQuirks.applyToBody("o3-mini", body())
    assertFalse("temperature" in fixed)
    assertFalse("top_p" in fixed)
    assertFalse("stop" in fixed)
  }

  @Test
  fun `лимит ответа переименовывается со СВОИМ значением, а не выбрасывается`() {
    val fixed = ModelQuirks.applyToBody("o3-mini", body())
    assertFalse("max_tokens" in fixed)
    assertEquals(JsonPrimitive(4096), fixed["max_completion_tokens"]?.jsonPrimitive,
                 "число попросил человек — подменять его дефолтом провайдера нельзя")
  }

  @Test
  fun `префикс роутера не прячет квирк`() {
    assertEquals(ModelQuirks.quirksOf("o3-mini"), ModelQuirks.quirksOf("openai/o3-mini"))
    assertTrue(ModelQuirks.has("azure/gpt-5-mini", ModelQuirks.Quirk.MAX_COMPLETION_TOKENS))
  }

  @Test
  fun `модель без стрима — стрим убирается из тела, а не остаётся врать`() {
    assertFalse(ModelQuirks.supportsStreaming("o1-preview"))
    assertTrue(ModelQuirks.supportsStreaming("o3-mini"))
    assertFalse("stream" in ModelQuirks.applyToBody("o1-preview", body()))
  }

  @Test
  fun `системная роль складывается в первое сообщение, а не теряется`() {
    val messages = listOf(
      ChatMessage("system", "правила проекта"),
      ChatMessage("user", "почини тест"),
    )
    val folded = ModelQuirks.applyToMessages("o1-preview", messages)
    assertEquals(2, folded.size)
    assertEquals("user", folded.first().role)
    assertTrue(folded.first().text.contains("правила проекта"), "системный промпт — правила всей сессии, терять его нельзя")
    // Модель, принимающая системную роль, ничего не теряет и не меняет.
    assertEquals(messages, ModelQuirks.applyToMessages("gpt-4o", messages))
  }

  @Test
  fun `у каждого правила каталога есть человеческое объяснение`() {
    assertTrue(ModelQuirks.RULES.isNotEmpty())
    assertTrue(ModelQuirks.RULES.all { it.note.isNotBlank() })
    assertTrue(ModelQuirks.RULES.all { it.quirks.isNotEmpty() })
  }

  @Test
  fun `пустой идентификатор не считается совпадением`() {
    assertTrue(ModelQuirks.quirksOf("").isEmpty())
    assertTrue(ModelQuirks.quirksOf("   ").isEmpty())
  }
}
