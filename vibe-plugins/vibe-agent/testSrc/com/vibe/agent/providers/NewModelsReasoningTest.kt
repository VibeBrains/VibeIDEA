// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.vibe.agent.providers.ReasoningMode.Level
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Models that arrived without a rule behaved differently from what the slider said, «off» first of all:
 * Kimi K3 from the vendor's list ran at max, Claude Sonnet 5.5 at the vendor's high, GPT-6.1 Sol failed tools on
 * chat/completions. Each is checked on the fields the request carries, with no declaration in the entry
 */
class NewModelsReasoningTest {
  private fun fields(wire: String, level: Level, id: String): JsonObject =
    LlmClient.reasoningFields(wire, level, id, declared = null, maxOutputTokens = 64_000)

  private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

  @Test
  fun `kimi k3 from the vendor's list cannot be switched off and speaks low, high and max`() {
    for (id in listOf("kimi-k3", "k3", "k3-256k")) {
      assertEquals(obj("""{"reasoning_effort":"low"}"""), fields("openai", Level.OFF, id), id)
      assertEquals(obj("""{"reasoning_effort":"high"}"""), fields("openai", Level.MEDIUM, id), id)
      assertEquals(obj("""{"reasoning_effort":"max"}"""), fields("openai", Level.HIGH, id), id)
    }
  }

  @Test
  fun `kimi-for-coding switches off with none and keeps the same words`() {
    assertEquals(obj("""{"reasoning_effort":"none"}"""), fields("openai", Level.OFF, "kimi-for-coding"))
    assertEquals(obj("""{"reasoning_effort":"low"}"""), fields("openai", Level.LOW, "kimi-for-coding"))
    assertEquals(obj("""{"reasoning_effort":"max"}"""), fields("openai", Level.HIGH, "kimi-for-coding"))
    // The HighSpeed model always reasons, and no rule claims otherwise
    assertEquals(JsonObject(emptyMap()), fields("openai", Level.OFF, "kimi-for-coding-highspeed"))
  }

  @Test
  fun `sonnet 5-5 off is between_tools, and above off it is adaptive with an explicit effort`() {
    assertEquals(obj("""{"thinking":{"type":"between_tools"}}"""), fields("anthropic", Level.OFF, "claude-sonnet-5-5"))
    val medium = fields("anthropic", Level.MEDIUM, "claude-sonnet-5-5")
    assertEquals("adaptive", medium["thinking"]!!.jsonObject["type"].toString().trim('"'))
    assertEquals(obj("""{"effort":"medium"}"""), medium["output_config"])
    // Sonnet 5 keeps its own switch
    assertEquals(obj("""{"thinking":{"type":"disabled"}}"""), fields("anthropic", Level.OFF, "claude-sonnet-5"))
  }

  @Test
  fun `gpt-6-1 sol cannot be switched off and calls tools on the responses wire only`() {
    assertEquals(obj("""{"reasoning":{"effort":"low"}}"""), fields(ModelQuirks.WIRE_OPENAI_RESPONSES, Level.OFF, "gpt-6.1-sol"))
    assertEquals(ModelQuirks.ToolSupport.ONLY_ON_RESPONSES, ModelQuirks.toolSupport("gpt-6.1-sol", ModelQuirks.WIRE_OPENAI))
    assertEquals(ModelQuirks.ToolSupport.YES, ModelQuirks.toolSupport("gpt-6.1-sol", ModelQuirks.WIRE_OPENAI_RESPONSES))
    // Sol and Luna of GPT-6 keep their rule
    assertEquals(obj("""{"reasoning_effort":"none"}"""), fields("openai", Level.OFF, "gpt-6-sol"))
  }

  @Test
  fun `sonnet 5-5 refuses forced tool use and the thinking switch in extraBody`() {
    val conflicts = ExtraBodyConflicts.of("claude-sonnet-5-5", "anthropic",
      obj("""{"tool_choice":{"type":"any"},"thinking":{"type":"disabled"}}""")).map { it.reason }.toSet()
    assertEquals(setOf(ExtraBodyConflicts.Reason.FORCED_TOOL, ExtraBodyConflicts.Reason.SWITCH), conflicts)
  }
}
