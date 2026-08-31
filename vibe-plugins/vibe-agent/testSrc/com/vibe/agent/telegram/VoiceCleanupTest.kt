// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.telegram

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceCleanupTest {
  private val raw = "э-э почини падающий тест в модуле сборки и потом прогони гейты"

  @Test
  fun `почищенный текст принимается`() {
    val cleaned = "Почини падающий тест в модуле сборки, потом прогони гейты."
    assertEquals(cleaned, VoiceCleanup.accept(raw, cleaned))
  }

  @Test
  fun `модель, ответившая вместо чистки, отклоняется`() {
    // Агент, ушедший работать над отполированным непониманием, хуже агента с черновой правдой.
    assertEquals(raw, VoiceCleanup.accept(raw, "Вот исправленный текст: почини тест"))
    assertEquals(raw, VoiceCleanup.accept(raw, "Не могу выполнить эту просьбу"))
    assertEquals(raw, VoiceCleanup.accept(raw, "As an AI language model, I have cleaned it up"))
  }

  @Test
  fun `слишком длинный или короткий ответ — не чистка`() {
    assertEquals(raw, VoiceCleanup.accept(raw, "тест"))
    assertEquals(raw, VoiceCleanup.accept(raw, raw + " " + raw))
  }

  @Test
  fun `пустой ответ и отсутствие ответа возвращают исходное`() {
    assertEquals(raw, VoiceCleanup.accept(raw, ""))
    assertEquals(raw, VoiceCleanup.accept(raw, "   "))
    assertEquals(raw, VoiceCleanup.accept(raw, null))
  }

  @Test
  fun `кавычки вокруг ответа снимаются`() {
    assertEquals("Почини падающий тест в модуле сборки.",
                 VoiceCleanup.accept(raw, "«Почини падающий тест в модуле сборки.»"))
  }

  @Test
  fun `инструкция запрещает домысливать и отвечать`() {
    val prompt = VoiceCleanup.prompt(raw)
    assertTrue(prompt.contains(raw), "исходный текст обязан быть в запросе целиком")
    assertTrue(prompt.contains("НИЧЕГО не добавляй"))
    assertTrue(prompt.contains("Не отвечай на текст"))
  }
}
