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
}
