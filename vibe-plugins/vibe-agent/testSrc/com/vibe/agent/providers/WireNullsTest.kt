// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Explicit nulls on the wire.
 *
 * `JsonNull` is a value, not a Kotlin `null`, so `element?.jsonObject` passes it through and the cast throws
 * «Element class kotlinx.serialization.json.JsonNull is not a JsonObject». Providers send such nulls as a matter of
 * course — an OpenAI-compatible stream carries `"usage": null` in every chunk but the last — and the throw ended the
 * whole turn with a stack trace in the feed (caught on MiniMax's Anthropic endpoint, two machines, 18.09.2026).
 */
class WireNullsTest {
  private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

  @Test
  fun `an OpenAI chunk with a null usage is «no usage», not a crash`() {
    assertNull(TokenUsage.fromOpenAiChunk(obj("""{"choices":[{"delta":{"content":"привет"}}],"usage":null}""")))
    assertEquals(7, TokenUsage.fromOpenAiChunk(obj("""{"usage":{"prompt_tokens":3,"completion_tokens":4}}"""))?.total)
  }

  @Test
  fun `an Anthropic event with a null usage or a null message is read the same way`() {
    assertNull(TokenUsage.fromAnthropicEvent(obj("""{"type":"message_delta","usage":null}""")))
    assertNull(TokenUsage.fromAnthropicEvent(obj("""{"type":"message_start","message":null}""")))
    assertNull(TokenUsage.fromAnthropicEvent(obj("""{"type":"message_start","message":{"usage":null}}""")))
    assertEquals(3, TokenUsage.fromAnthropicEvent(obj("""{"message":{"usage":{"input_tokens":3}}}"""))?.total)
  }

  @Test
  fun `a null delta or a null content block carries no reasoning and no tool call`() {
    assertNull(ReasoningStream.fromAnthropicEvent(obj("""{"type":"content_block_delta","delta":null}""")))
    assertNull(ReasoningStream.fromOpenAiChunk(obj("""{"choices":[{"delta":null}]}""")))
    assertNull(ReasoningStream.fromOpenAiChunk(obj("""{"choices":null}""")))
    assertNull(ReasoningStream.fromGeminiEvent(obj("""{"candidates":[{"content":null}]}""")))
    // A tool-call accumulator must survive the same shapes: nothing collected, nothing thrown.
    val calls = ToolCallAccumulator()
    calls.openAiChunk(obj("""{"choices":[{"delta":null}]}"""))
    calls.geminiEvent(obj("""{"candidates":[{"content":null}]}"""))
    calls.anthropicEvent(obj("""{"type":"content_block_delta","index":0,"delta":null}"""))
    assertEquals(emptyList(), calls.calls())
  }

  @Test
  fun `a model name is not read out of a null message`() {
    assertNull(ModelEcho.fromAnthropicEvent(obj("""{"type":"message_start","message":null}""")))
    assertEquals("MiniMax-M3", ModelEcho.fromAnthropicEvent(obj("""{"message":{"model":"MiniMax-M3"}}""")))
  }
}
