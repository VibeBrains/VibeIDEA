// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ModelQuirksWireTest {
  private fun anthropicBody() = buildJsonObject {
    put("max_tokens", 4096)
    put("temperature", 0.7)
    put("top_k", 40)
    put("stop_sequences", JsonArray(listOf(JsonPrimitive("END"))))
  }

  @Test
  fun `the stop list is a different field on each wire`() {
    // Applying the OpenAI names to an Anthropic body removes nothing and sends exactly what the
    // model rejects — a quirk catalogue that decorates instead of working.
    val out = ModelQuirks.applyToBody("minimax-m3", anthropicBody(), wire = ModelQuirks.WIRE_ANTHROPIC)
    assertNull(out["stop_sequences"])
    assertNull(out["top_k"])
    // MiniMax accepts these two, and the catalogue must not take them away.
    assertEquals(0.7, out.getValue("temperature").toString().toDouble())
    assertEquals(4096, out.getValue("max_tokens").toString().toInt())
  }

  @Test
  fun `the answer limit is never renamed on the anthropic wire`() {
    // `max_tokens` is Anthropic's own field and it is REQUIRED: renaming it would produce a
    // request with no answer limit at all.
    val file = ModelQuirksFile.rules(
      ModelQuirksFile.parse("""{"models":[{"match":"^m","quirks":["MAX_COMPLETION_TOKENS"]}]}""", "t") { }
    )
    val out = ModelQuirks.applyToBody("m1", anthropicBody(), file, ModelQuirks.WIRE_ANTHROPIC)
    assertNotNull(out["max_tokens"])
    assertNull(out["max_completion_tokens"])

    // On the OpenAI wire the same quirk still renames it, value intact.
    val openAi = buildJsonObject { put("max_tokens", 4096) }
    val renamed = ModelQuirks.applyToBody("m1", openAi, file, ModelQuirks.WIRE_OPENAI)
    assertEquals(4096, renamed.getValue("max_completion_tokens").toString().toInt())
    assertNull(renamed["max_tokens"])
  }

  @Test
  fun `the openai wire stays the default so nothing silently changed`() {
    val out = ModelQuirks.applyToBody("minimax-m3", buildJsonObject {
      put("stop", JsonPrimitive("END"))
      put("top_k", 40)
    })
    assertNull(out["stop"])
    assertNull(out["top_k"])
  }
}
