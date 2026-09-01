// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ModelProtocolTest {
  @Test
  fun `the model has the last word about its own protocol`() {
    // The case this exists for: OpenCode Go serves MiniMax and Qwen over an Anthropic-compatible
    // /v1/messages and GLM/Kimi over /v1/chat/completions — one provider, one key, one base URL.
    assertEquals("anthropic", ProvidersService.protocolFor("openai", "anthropic"))
    assertEquals("openai", ProvidersService.protocolFor("anthropic", "openai"))
  }

  @Test
  fun `silence inherits the provider`() {
    // Nothing changes for the ninety-nine providers that speak one protocol.
    assertEquals("anthropic", ProvidersService.protocolFor("anthropic", null))
    assertEquals("gemini", ProvidersService.protocolFor("gemini"))
    assertEquals("openai", ProvidersService.protocolFor(null))
  }

  @Test
  fun `an unrecognised name falls back instead of failing`() {
    // A file written for a newer IDE must not take the registry down; a wrong protocol fails
    // loudly on the first request anyway, naming the endpoint.
    assertEquals("openai", ProvidersService.protocolFor("openai", "responses"))
    assertEquals("openai", ProvidersService.protocolFor("quantum"))
  }

  @Test
  fun `a model protocol survives the file layers`() {
    val base = ProvidersFile.parse(
      """{"providers":[{"id":"go","protocol":"openai","baseURL":"https://x/v1",
         "models":{"static":[{"id":"minimax-m3","protocol":"anthropic"},{"id":"glm-5.3"}]}}]}""",
      "global",
    ) { }
    val over = ProvidersFile.parse(
      """{"providers":[{"id":"go","models":{"static":[{"id":"minimax-m3","note":"мой"}]}}]}""",
      "project",
    ) { }
    val merged = ProvidersFile.merge(base, over).single()
    val minimax = merged.models.first { it.id == "minimax-m3" }
    // The project layer said nothing about the protocol, so it must not lose the one it had —
    // otherwise adding a note to a model would silently break the way it is called.
    assertEquals("anthropic", minimax.protocol)
    assertEquals("мой", minimax.note)
    assertEquals(null, merged.models.first { it.id == "glm-5.3" }.protocol)
  }
}
