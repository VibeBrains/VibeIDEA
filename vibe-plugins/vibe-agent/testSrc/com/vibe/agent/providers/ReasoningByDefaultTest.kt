// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Models that reason when nothing is sent: «off» has to say so in the vendor's words, or it is not off.
 *
 * Opus 5.5, GPT-6 Sol/Luna and MiMo v2.6 all think by default; an «off» that sends nothing leaves them thinking, and
 * the person pays for reasoning they switched off. The cases below pin what each family receives, end to end through
 * [LlmClient.reasoningFields], because the declaration, the catalogue, the clamp and the dialect only mean something
 * together.
 */
class ReasoningByDefaultTest {
  private val off = ReasoningMode.Level.OFF
  private val high = ReasoningMode.Level.HIGH

  private fun fields(id: String, wire: String, asked: ReasoningMode.Level, declared: ReasoningMode.Support? = null): JsonObject =
    LlmClient.reasoningFields(wire, asked, id, declared, maxOutputTokens = 64_000)

  private fun JsonObject.path(vararg keys: String): String? {
    var node: JsonObject = this
    for (key in keys.dropLast(1)) node = node[key]?.jsonObject ?: return null
    return node[keys.last()]?.jsonPrimitive?.content
  }

  @Test
  fun `opus 5-5, fable and mythos cannot switch thinking off, so off sends the lowest effort`() {
    for (id in listOf("claude-opus-5-5", "claude-fable-5", "claude-fable-5-1", "claude-mythos-5-1")) {
      val body = fields(id, "anthropic", off)
      assertEquals("adaptive", body.path("thinking", "type"), id)
      assertEquals("low", body.path("output_config", "effort"), id)
    }
  }

  @Test
  fun `opus 5 and sonnet 5 accept the switch, so off sends it on the anthropic wire`() {
    for (id in listOf("claude-opus-5", "claude-sonnet-5")) {
      val body = fields(id, "anthropic", off)
      assertEquals("disabled", body.path("thinking", "type"), id)
      assertNull(body["output_config"], id)
    }
  }

  @Test
  fun `the anthropic switch is not sent to a router speaking chat completions`() {
    // A router has its own spelling of the switch; a field it does not know would be our guess.
    assertTrue(fields("anthropic/claude-opus-5", "openai", off).isEmpty())
  }

  @Test
  fun `gpt-6 sol and luna are switched off with effort none, the only level at which they take tools`() {
    for (id in listOf("gpt-6-sol", "gpt-6-luna", "openai/gpt-6-luna")) {
      assertEquals("none", fields(id, "openai", off).path("reasoning_effort"), id)
    }
  }

  @Test
  fun `gpt-6 astra refuses none, so off sends low, and it takes no tools on chat completions`() {
    assertEquals("low", fields("gpt-6-astra", "openai", off).path("reasoning_effort"))
    assertTrue(ModelQuirks.has("gpt-6-astra", ModelQuirks.Quirk.NO_TOOLS))
    assertFalse(ModelQuirks.has("gpt-6-sol", ModelQuirks.Quirk.NO_TOOLS))
  }

  @Test
  fun `mimo takes no level, so every position above off sends nothing`() {
    for (level in listOf(ReasoningMode.Level.LOW, ReasoningMode.Level.MEDIUM, high)) {
      assertTrue(fields("mimo-v2.6-pro", "openai", level).isEmpty(), level.name)
    }
  }

  @Test
  fun `mimo is switched off by the declaration its entry carries`() {
    val declared = ReasoningMode.Support(off = buildJsonObject { put("thinking", buildJsonObject { put("type", "disabled") }) })
    assertEquals("disabled", fields("mimo-v2.6-flash", "openai", off, declared).path("thinking", "type"))
  }

  @Test
  fun `a declaration wins over the catalogue field by field`() {
    val known = ReasoningMode.Support(off = buildJsonObject { put("reasoning_effort", "none") })
    val declared = ReasoningMode.Support(words = listOf("low", "medium", "high", "xhigh", "max"))
    val merged = ReasoningMode.merged(declared, known)!!
    assertEquals(declared.words, merged.words)
    assertEquals(known.off, merged.off)
    // A person who writes that the model can be switched off overrides a catalogue that says it cannot.
    val stated = ReasoningMode.Support(canTurnOff = true)
    assertEquals(true, ReasoningMode.merged(stated, ReasoningMode.Support(canTurnOff = false))!!.canTurnOff)
  }

  @Test
  fun `a model the catalogue knows nothing about keeps the old behaviour`() {
    assertTrue(fields("claude-opus-4-8", "anthropic", off).isEmpty())
    assertTrue(fields("some-local-model", "openai", off).isEmpty())
    assertNull(ModelQuirks.reasoningOf("claude-opus-4-8", ModelQuirks.WIRE_ANTHROPIC))
  }

  @Test
  fun `the adaptive mode asks for a summarized display`() {
    // Omitted is the default on these models: the thinking arrives with empty text and the reasoning block stays blank.
    assertEquals("summarized", fields("claude-opus-5-5", "anthropic", high).path("thinking", "display"))
    assertEquals("summarized", fields("claude-opus-4-7", "anthropic", high).path("thinking", "display"))
  }

  @Test
  fun `claude 4-7 and later drop the sampling knobs`() {
    for (id in listOf("claude-opus-4-7", "claude-opus-4-8", "claude-opus-5", "claude-opus-5-5", "claude-sonnet-5", "claude-fable-5-1")) {
      assertTrue(ModelQuirks.has(id, ModelQuirks.Quirk.NO_SAMPLING), id)
    }
    // 4.6 and earlier still take them.
    assertFalse(ModelQuirks.has("claude-opus-4-6", ModelQuirks.Quirk.NO_SAMPLING))
    val body = ModelQuirks.applyToBody("claude-opus-5-5",
      buildJsonObject { put("temperature", 0.2); put("top_p", 0.9); put("top_k", 40); put("max_tokens", 1000) },
      wire = ModelQuirks.WIRE_ANTHROPIC)
    assertEquals(setOf("max_tokens"), body.keys)
  }
}
