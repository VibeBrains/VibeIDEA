// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelQuirksGranularTest {
  private fun body() = buildJsonObject {
    put("temperature", 0.7)
    put("top_p", 0.95)
    put("top_k", 40)
    put("stop", JsonPrimitive("END"))
    put("max_tokens", 1000)
  }

  @Test
  fun `a single knob can be taken away without the other two`() {
    // The case the aggregate could not express: MiniMax accepts temperature and top_p and ignores
    // top_k. Describing that with NO_SAMPLING would silently drop settings the model honours.
    val out = ModelQuirks.applyToBody("minimax-m3", body())
    assertEquals(0.7, out.getValue("temperature").toString().toDouble())
    assertEquals(0.95, out.getValue("top_p").toString().toDouble())
    assertNull(out["top_k"])
    assertNull(out["stop"])
  }

  @Test
  fun `the aggregate still takes all three`() {
    // Old files keep working: NO_SAMPLING was not replaced, only joined by finer names.
    assertTrue(ModelQuirks.Quirk.NO_SAMPLING in ModelQuirks.quirksOf("gpt-5", emptyList()))
    val out = ModelQuirks.applyToBody("gpt-5", body())
    assertNull(out["temperature"])
    assertNull(out["top_p"])
    assertNull(out["top_k"])
  }

  @Test
  fun `each fine-grained name touches only its own field`() {
    val file = ModelQuirksFile.rules(
      ModelQuirksFile.parse("""{"models":[{"match":"^solo","quirks":["NO_TEMPERATURE"]}]}""", "t") { }
    )
    ModelQuirks.setOverrides(file)
    try {
      val out = ModelQuirks.applyToBody("solo-1", body())
      assertNull(out["temperature"])
      assertEquals(0.95, out.getValue("top_p").toString().toDouble())
      assertEquals(40, out.getValue("top_k").toString().toInt())
    }
    finally {
      ModelQuirks.setOverrides(emptyList())
    }
  }

  @Test
  fun `nothing was inferred from the reported SSE symptom`() {
    // Two projects reported MiniMax streaming oddities and neither named the events. A quirk
    // guessed from someone else's symptom rewrites our requests blindly, so streaming stays on.
    assertTrue(ModelQuirks.supportsStreaming("minimax-m3"))
  }
}
