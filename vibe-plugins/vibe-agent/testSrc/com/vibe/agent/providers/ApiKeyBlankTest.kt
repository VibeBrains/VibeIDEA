// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Пустое значение ключом не считается.
 *
 * `ZAI_API_KEY=` в `.vibe/.env` — законная строка файла и не ключ. Раньше она побеждала как
 * значение: запрос уходил с пустым Bearer, вендор отвечал «нет ключа», а IDE, проверявшая на null и
 * получившая строку, не говорила ничего (принёс брат владельца 18.09.2026).
 */
class ApiKeyBlankTest {
  @Test
  fun `the slot and the variable are named for the person`() {
    assertEquals("minimax / MINIMAX_API_KEY",
                 ApiKeyResolver.sourceNames(ProviderEntry(id = "minimax-anthropic", apiKeyRef = "minimax",
                                                          apiKeyEnv = "MINIMAX_API_KEY")))
    // Без ref ключ лежит в слоте с именем провайдера — это и есть место, куда пишет страница настроек.
    assertEquals("ollama", ApiKeyResolver.sourceNames(ProviderEntry(id = "ollama")))
  }
}
