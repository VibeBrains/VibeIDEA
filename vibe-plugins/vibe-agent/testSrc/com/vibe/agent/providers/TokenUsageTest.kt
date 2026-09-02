// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenUsageTest {
  private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

  @Test
  fun `the anthropic wire reports input at the start and output at the end`() {
    val start = TokenUsage.fromAnthropicEvent(obj("""
      {"type":"message_start","message":{"usage":{"input_tokens":1200,"cache_read_input_tokens":8000,
       "cache_creation_input_tokens":300,"output_tokens":1}}}
    """))!!
    val delta = TokenUsage.fromAnthropicEvent(obj("""{"type":"message_delta","usage":{"output_tokens":450}}"""))!!
    val merged = start.merge(delta)
    assertEquals(1200, merged.inputTokens)
    assertEquals(8000, merged.cacheReadTokens)
    assertEquals(300, merged.cacheWriteTokens)
    assertEquals(450, merged.outputTokens)
  }

  @Test
  fun `merging takes the maximum, so a repeated running total is not doubled`() {
    val first = TokenUsage(inputTokens = 100, outputTokens = 10)
    val repeated = TokenUsage(inputTokens = 100, outputTokens = 40)
    assertEquals(TokenUsage(inputTokens = 100, outputTokens = 40), first.merge(repeated))
  }

  @Test
  fun `the openai wire counts cached tokens inside prompt_tokens`() {
    // Left as-is they would be billed twice — once at the input price and once at the cache rate.
    val usage = TokenUsage.fromOpenAiChunk(obj("""
      {"usage":{"prompt_tokens":10000,"completion_tokens":500,"prompt_tokens_details":{"cached_tokens":9000}}}
    """))!!
    assertEquals(1000, usage.inputTokens)
    assertEquals(9000, usage.cacheReadTokens)
    assertEquals(500, usage.outputTokens)
    assertEquals(10500, usage.total)
  }

  @Test
  fun `an event without usage says nothing rather than zero`() {
    // Zero would read as «бесплатный ход» and quietly replace a number we simply do not have.
    assertNull(TokenUsage.fromOpenAiChunk(obj("""{"choices":[{"delta":{"content":"hi"}}]}""")))
    assertNull(TokenUsage.fromAnthropicEvent(obj("""{"type":"content_block_delta","delta":{"text":"hi"}}""")))
    assertNull(TokenUsage.fromOpenAiChunk(obj("""{"usage":{"prompt_tokens":0,"completion_tokens":0}}""")))
    assertTrue(!TokenUsage.NONE.known)
  }
}
