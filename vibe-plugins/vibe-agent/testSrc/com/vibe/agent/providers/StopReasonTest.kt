// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Why an answer ended, read from each wire; an abnormal end must not pass for a finished answer. */
class StopReasonTest {
  private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

  @Test
  fun `anthropic says it at the end of the stream`() {
    val reason = StopReason.fromAnthropicEvent(json("""{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}"""))!!
    assertEquals(StopReason.Kind.LENGTH, reason.kind)
    assertTrue(reason.abnormal)
    assertNull(StopReason.fromAnthropicEvent(json("""{"type":"message_start","message":{"stop_reason":null}}""")))
    assertNull(StopReason.fromAnthropicEvent(json("""{"type":"content_block_delta","delta":{"type":"text_delta","text":"x"}}""")))
  }

  @Test
  fun `a refusal carries its category and explanation, in the stream and in a whole response`() {
    val streamed = StopReason.fromAnthropicEvent(json(
      """{"type":"message_delta","delta":{"stop_reason":"refusal","stop_details":{"type":"refusal","category":"bio","explanation":"Declined."}}}"""))!!
    assertEquals(StopReason.Kind.REFUSAL, streamed.kind)
    assertEquals("bio", streamed.category)
    assertEquals("Declined.", streamed.explanation)
    val whole = StopReason.fromAnthropicEvent(json(
      """{"type":"message","content":[],"stop_reason":"refusal","stop_details":{"type":"refusal","category":null,"explanation":null}}"""))!!
    assertEquals(StopReason.Kind.REFUSAL, whole.kind)
    // A refusal outside the named categories is a normal answer of the vendor, not a missing field.
    assertNull(whole.category)
  }

  @Test
  fun `finishing and calling tools are not abnormal`() {
    assertFalse(StopReason.fromAnthropicEvent(json("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}"""))!!.abnormal)
    assertFalse(StopReason.fromAnthropicEvent(json("""{"type":"message_delta","delta":{"stop_reason":"tool_use"}}"""))!!.abnormal)
    assertFalse(StopReason.fromOpenAiChunk(json("""{"choices":[{"finish_reason":"tool_calls"}]}"""))!!.abnormal)
    assertFalse(StopReason.fromGeminiEvent(json("""{"candidates":[{"finishReason":"STOP"}]}"""))!!.abnormal)
  }

  @Test
  fun `chat completions reasons, mimo's own included`() {
    assertEquals(StopReason.Kind.LENGTH, StopReason.fromOpenAiChunk(json("""{"choices":[{"finish_reason":"length"}]}"""))!!.kind)
    assertEquals(StopReason.Kind.CONTENT_FILTER,
                 StopReason.fromOpenAiChunk(json("""{"choices":[{"finish_reason":"content_filter"}]}"""))!!.kind)
    assertEquals(StopReason.Kind.REPETITION,
                 StopReason.fromOpenAiChunk(json("""{"choices":[{"finish_reason":"repetition_truncation"}]}"""))!!.kind)
    assertNull(StopReason.fromOpenAiChunk(json("""{"choices":[{"delta":{"content":"x"},"finish_reason":null}]}""")))
  }

  @Test
  fun `a reason nobody taught us is kept as the vendor spelled it`() {
    val reason = StopReason.fromOpenAiChunk(json("""{"choices":[{"finish_reason":"insufficient_system_resource"}]}"""))!!
    assertEquals(StopReason.Kind.OTHER, reason.kind)
    assertEquals("insufficient_system_resource", reason.raw)
    assertTrue(reason.abnormal)
  }

  @Test
  fun `gemini names its filters and its limit`() {
    assertEquals(StopReason.Kind.LENGTH, StopReason.fromGeminiEvent(json("""{"candidates":[{"finishReason":"MAX_TOKENS"}]}"""))!!.kind)
    assertEquals(StopReason.Kind.CONTENT_FILTER,
                 StopReason.fromGeminiEvent(json("""{"candidates":[{"finishReason":"SAFETY"}]}"""))!!.kind)
    assertNull(StopReason.fromGeminiEvent(json("""{"candidates":[{"finishReason":"FINISH_REASON_UNSPECIFIED"}]}""")))
  }
}
