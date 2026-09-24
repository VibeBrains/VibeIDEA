// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The reasoning dial behind OpenRouter: one `reasoning` object for every model it routes, and `effort: "none"` for off.
 *
 * On the plain OpenAI wire the dial says `reasoning_effort`, which OpenRouter does not document, and «off» says nothing
 * for a model whose entry names no switch — every catalogue model there — so MiMo, which reasons by default, went on
 * reasoning with the dial at «off». The cases run end to end through [LlmClient.reasoningFields].
 */
class OpenRouterReasoningTest {
  private val openRouter = ReasoningMode.dialectOf(ModelQuirks.WIRE_OPENAI, ReasoningMode.DIALECT_OPENROUTER)

  private fun fields(id: String, asked: ReasoningMode.Level, declared: ReasoningMode.Support? = null): JsonObject =
    LlmClient.reasoningFields(openRouter, asked, id, declared, maxOutputTokens = 64_000)

  private fun JsonObject.effort(): String? = this["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content

  @Test
  fun `the dialect applies only on the openai wire`() {
    assertEquals(ReasoningMode.DIALECT_OPENROUTER, openRouter)
    assertEquals(ModelQuirks.WIRE_ANTHROPIC, ReasoningMode.dialectOf(ModelQuirks.WIRE_ANTHROPIC, ReasoningMode.DIALECT_OPENROUTER))
    assertEquals(ModelQuirks.WIRE_OPENAI, ReasoningMode.dialectOf(ModelQuirks.WIRE_OPENAI, null))
  }

  @Test
  fun `each position of the dial goes as the reasoning object, never as reasoning_effort`() {
    for ((level, word) in listOf(ReasoningMode.Level.LOW to "low", ReasoningMode.Level.MEDIUM to "medium",
                                 ReasoningMode.Level.HIGH to "high")) {
      val body = fields("some-vendor/some-reasoner", level)
      assertEquals(word, body.effort(), level.name)
      assertNull(body["reasoning_effort"], level.name)
    }
  }

  @Test
  fun `off switches reasoning off with effort none, even for a model that declares nothing`() {
    assertEquals("none", fields("xiaomi/mimo-v2.6-pro", ReasoningMode.Level.OFF).effort())
    assertEquals("none", fields("some-vendor/some-model", ReasoningMode.Level.OFF).effort())
  }

  @Test
  fun `mimo has no levels, so a position above off sends nothing and leaves its default reasoning on`() {
    assertTrue(fields("xiaomi/mimo-v2.6-pro", ReasoningMode.Level.HIGH).isEmpty())
  }

  @Test
  fun `a model that cannot stop reasoning gets its lowest effort for off, not none`() {
    assertEquals("low", fields("anthropic/claude-opus-5-5", ReasoningMode.Level.OFF).effort())
  }

  @Test
  fun `an entry's own spelling of off still wins`() {
    val declared = ReasoningMode.Support(off = buildJsonObject { put("reasoning", buildJsonObject { put("enabled", false) }) })
    val body = fields("some-vendor/some-model", ReasoningMode.Level.OFF, declared)
    assertEquals("false", body["reasoning"]?.jsonObject?.get("enabled")?.jsonPrimitive?.content)
  }

  @Test
  fun `the declaration is read from the file, inherited through extends and kept by a layer that does not mention it`() {
    val parsed = ProvidersFile.parse("""{"providers":[
      {"id":"openrouter","protocol":"openai","baseURL":"https://openrouter.ai/api/v1","reasoningDialect":"openrouter"},
      {"id":"my-router","extends":"openrouter","name":"Mine"}
    ]}""") { fail("unexpected warning: $it") }
    val resolved = ProvidersFile.resolveExtends(parsed) { fail("unexpected warning: $it") }
    assertEquals(ReasoningMode.DIALECT_OPENROUTER, resolved.first { it.id == "my-router" }.reasoningDialect)
    val merged = ProvidersFile.merge(parsed, listOf(ProviderEntry(id = "openrouter", timeoutMs = 5_000)))
    assertEquals(ReasoningMode.DIALECT_OPENROUTER, merged.first { it.id == "openrouter" }.reasoningDialect)
  }

  @Test
  fun `an unknown dialect is dropped with a warning`() {
    val warnings = ArrayList<String>()
    val parsed = ProvidersFile.parse("""{"providers":[{"id":"x","protocol":"openai","reasoningDialect":"guess"}]}""") {
      warnings += it
    }
    assertNull(parsed.single().reasoningDialect)
    assertEquals(1, warnings.size, "$warnings")
  }
}
