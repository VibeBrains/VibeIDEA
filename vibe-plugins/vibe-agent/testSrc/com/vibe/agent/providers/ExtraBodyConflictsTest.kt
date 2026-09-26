// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An `extraBody` field the vendor refuses is named by the doctor, not discovered by the vendor's 400. */
class ExtraBodyConflictsTest {
  private fun body(text: String) = Json.parseToJsonElement(text).jsonObject

  private fun conflicts(id: String, wire: String, text: String) =
    ExtraBodyConflicts.of(id, wire, body(text)).map { it.field to it.reason }

  @Test
  fun `opus 5-5 refuses sampling and the thinking switch`() {
    assertEquals(
      listOf("temperature" to ExtraBodyConflicts.Reason.SAMPLING, "thinking.type" to ExtraBodyConflicts.Reason.SWITCH),
      conflicts("claude-opus-5-5", "anthropic", """{"temperature": 0.3, "thinking": {"type": "disabled"}}"""))
  }

  @Test
  fun `the 5 line refuses a token budget`() {
    assertEquals(listOf("thinking.type" to ExtraBodyConflicts.Reason.BUDGET),
                 conflicts("claude-opus-5", "anthropic", """{"thinking": {"type": "enabled", "budget_tokens": 8000}}"""))
    // Opus 5 does accept the switch — that is not a conflict.
    assertTrue(conflicts("claude-opus-5", "anthropic", """{"thinking": {"type": "disabled"}}""").isEmpty())
  }

  @Test
  fun `gpt-6 astra refuses none and sampling on chat completions`() {
    assertEquals(
      listOf("top_p" to ExtraBodyConflicts.Reason.SAMPLING, "reasoning_effort" to ExtraBodyConflicts.Reason.SWITCH),
      conflicts("gpt-6-astra", "openai", """{"reasoning_effort": "none", "top_p": 0.9}"""))
  }

  @Test
  fun `gpt-6 astra refuses effort none in the responses spelling too`() {
    assertEquals(listOf("reasoning.effort" to ExtraBodyConflicts.Reason.SWITCH),
                 conflicts("gpt-6-astra", "openai-responses", """{"reasoning": {"effort": "none"}}"""))
    // Sol takes it: that is how its reasoning goes off.
    assertTrue(conflicts("gpt-6-sol", "openai-responses", """{"reasoning": {"effort": "none"}}""").isEmpty())
  }

  @Test
  fun `a granular quirk names only the knob it is about`() {
    // MiniMax ignores top_k and takes temperature: only top_k is a conflict.
    assertTrue(conflicts("minimax-m3", "anthropic", """{"temperature": 0.7}""").isEmpty())
    assertEquals(listOf("top_k" to ExtraBodyConflicts.Reason.SAMPLING), conflicts("minimax-m3", "anthropic", """{"top_k": 40}"""))
  }

  @Test
  fun `no extraBody, no conflicts`() {
    assertTrue(ExtraBodyConflicts.of("claude-opus-5-5", "anthropic", null).isEmpty())
    assertTrue(conflicts("some-local-model", "openai", """{"temperature": 0.2}""").isEmpty())
  }

  @Test
  fun `opus 5-5, fable 5-1 and mythos 5-1 refuse forced tool use, auto is fine`() {
    for (id in listOf("claude-opus-5-5", "claude-fable-5-1", "claude-mythos-5-1")) {
      for (type in listOf("any", "tool")) {
        assertEquals(listOf("tool_choice.type" to ExtraBodyConflicts.Reason.FORCED_TOOL),
                     conflicts(id, "anthropic", """{"tool_choice": {"type": "$type", "name": "x"}}"""), "$id $type")
      }
      assertTrue(conflicts(id, "anthropic", """{"tool_choice": {"type": "auto"}}""").isEmpty(), id)
    }
    // Opus 5 is not on the vendor's list
    assertTrue(conflicts("claude-opus-5", "anthropic", """{"tool_choice": {"type": "any"}}""").isEmpty())
  }

  @Test
  fun `the vendor's fallback is named on anthropic's wire for any model, and ignored elsewhere`() {
    assertEquals(listOf("fallbacks" to ExtraBodyConflicts.Reason.SERVER_FALLBACK),
                 conflicts("claude-sonnet-4-5", "anthropic", """{"fallbacks": "default"}"""))
    assertTrue(conflicts("gpt-6-sol", "openai", """{"fallbacks": "default"}""").isEmpty())
  }
}
