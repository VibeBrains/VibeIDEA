// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Токены модели-оркестратора считаются наравне с обычными.
 *
 * Вендор биллит их по той же цене, поэтому пропуск этих полей занижает стоимость хода молча — а
 * заниженная сумма уходит в журнал расхода и в потолок трат.
 */
class TokenUsageOrchestrationTest {
  private fun chunk(json: String) = Json.parseToJsonElement(json).jsonObject

  @Test
  fun `оркестрационные токены прибавляются к ходу`() {
    val usage = TokenUsage.fromOpenAiChunk(chunk("""
      {"usage":{"prompt_tokens":1000,"completion_tokens":200,
        "orchestration_input_tokens":5000,"orchestration_output_tokens":800,
        "orchestration_input_cached_tokens":1500,"total_tokens":7000}}
    """))!!
    assertEquals(1000 + (5000 - 1500), usage.inputTokens)
    assertEquals(200 + 800, usage.outputTokens)
    assertEquals(1500, usage.cacheReadTokens)
  }

  @Test
  fun `обычный ответ без оркестрации считается как прежде`() {
    val usage = TokenUsage.fromOpenAiChunk(chunk("""
      {"usage":{"prompt_tokens":1000,"completion_tokens":200,
        "prompt_tokens_details":{"cached_tokens":400}}}
    """))!!
    assertEquals(600, usage.inputTokens)
    assertEquals(200, usage.outputTokens)
    assertEquals(400, usage.cacheReadTokens)
  }

  @Test
  fun `the nested token_details shape is read the same way`() {
    val usage = TokenUsage.fromOpenAiChunk(chunk("""
      {"usage":{"prompt_tokens":1000,"completion_tokens":200,
        "token_details":{"orchestration_input_tokens":5000,"orchestration_output_tokens":800,
          "orchestration_input_cached_tokens":1500}}}
    """))!!
    assertEquals(1000 + (5000 - 1500), usage.inputTokens)
    assertEquals(200 + 800, usage.outputTokens)
    assertEquals(1500, usage.cacheReadTokens)
  }

  @Test
  fun `when both shapes arrive the tokens are not counted twice`() {
    val usage = TokenUsage.fromOpenAiChunk(chunk("""
      {"usage":{"prompt_tokens":1000,"completion_tokens":200,
        "orchestration_input_tokens":5000,"orchestration_output_tokens":800,
        "token_details":{"orchestration_input_tokens":5000,"orchestration_output_tokens":800}}}
    """))!!
    assertEquals(6000, usage.inputTokens)
    assertEquals(1000, usage.outputTokens)
  }

  @Test
  fun `DeepSeek cache hits count when cached_tokens is absent`() {
    val nested = TokenUsage.fromOpenAiChunk(chunk("""
      {"usage":{"prompt_tokens":1000,"completion_tokens":10,
        "prompt_tokens_details":{"prompt_cache_hit_tokens":800,"prompt_cache_miss_tokens":200}}}
    """))!!
    assertEquals(200, nested.inputTokens)
    assertEquals(800, nested.cacheReadTokens)
    val flat = TokenUsage.fromOpenAiChunk(chunk("""
      {"usage":{"prompt_tokens":1000,"completion_tokens":10,"prompt_cache_hit_tokens":800,"prompt_cache_miss_tokens":200}}
    """))!!
    assertEquals(200, flat.inputTokens)
    assertEquals(800, flat.cacheReadTokens)
  }

  @Test
  fun `cached_tokens wins over the hit count when both arrive`() {
    val usage = TokenUsage.fromOpenAiChunk(chunk("""
      {"usage":{"prompt_tokens":1000,"completion_tokens":10,
        "prompt_tokens_details":{"cached_tokens":600,"prompt_cache_hit_tokens":800}}}
    """))!!
    assertEquals(600, usage.cacheReadTokens)
  }
}
