// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Who actually answered: the model name read off each wire
 * The comparison with the requested id is pinned by the shared vectors ([ModelEchoVectorsTest]); a new case goes there
 */
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
}
