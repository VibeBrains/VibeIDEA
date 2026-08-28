// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.vibe.agent.providers.ModelEntry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelRowsTest {
  @Test
  fun badgesReflectDeclaredTraits() {
    val m = ModelEntry(id = "glm-4.5v", name = "GLM-4.5V", vision = true, fim = false, contextWindow = 65536)
    assertEquals(listOf("кастом", "vision", "64K"), ModelRows.badges(m, custom = true))
    val textOnly = ModelEntry(id = "deepseek-v4-pro", vision = false, contextWindow = 1_000_000)
    assertEquals(listOf("text-only", "1M"), ModelRows.badges(textOnly, custom = false))
    val fim = ModelEntry(id = "qwen-coder", fim = true)
    assertEquals(listOf("fim"), ModelRows.badges(fim, custom = false))
    // Unknown vision (tri-state null) adds no badge at all.
    assertEquals(emptyList(), ModelRows.badges(ModelEntry(id = "bare"), custom = false))
  }

  @Test
  fun contextFormatsHumanReadable() {
    assertEquals("128K", ModelRows.formatContext(131072))
    assertEquals("1M", ModelRows.formatContext(1_000_000))
    assertEquals("1M", ModelRows.formatContext(1_048_576))
    assertEquals("256K", ModelRows.formatContext(262144))
    assertEquals("200K", ModelRows.formatContext(200_000))
    assertEquals("500", ModelRows.formatContext(500))
  }

  @Test
  fun searchIsAndTokenizedCaseInsensitive() {
    val hay = "Z.AI (GLM) zai " + ModelRows.label("GLM-4.5V", "glm-4.5v", listOf("кастом", "vision", "65K"))
    assertTrue(ModelRows.matches(hay, ModelRows.tokens("glm vision")))
    assertTrue(ModelRows.matches(hay, ModelRows.tokens("КАСТОМ 4.5v")))
    assertFalse(ModelRows.matches(hay, ModelRows.tokens("glm fim")))
    // Empty query matches everything.
    assertTrue(ModelRows.matches(hay, ModelRows.tokens("  ")))
  }

  @Test
  fun labelJoinsNameIdAndBadges() {
    assertEquals("GLM-4.6  ·  glm-4.6  · кастом", ModelRows.label("GLM-4.6", "glm-4.6", listOf("кастом")))
    // Name equal to id is not repeated.
    assertEquals("glm-5.3", ModelRows.label("glm-5.3", "glm-5.3", emptyList()))
  }

  @Test
  fun counterSwitchesToFoundOverTotalWhileSearching() {
    assertEquals("(12)", ModelRows.counter(found = 12, totalAfterActives = 12, searching = false))
    assertEquals("(3/12)", ModelRows.counter(found = 3, totalAfterActives = 12, searching = true))
  }

  @Test
  fun visibilityDefaultsHideCatalogModelsUntilExplicitlyEnabled() {
    // No stored decision: a catalog-only model falls back to hidden, a custom one to visible.
    assertTrue(ModelVisibility.effectiveHidden(stored = null, defaultHidden = true))
    assertFalse(ModelVisibility.effectiveHidden(stored = null, defaultHidden = false))
    // An explicit user toggle always beats the default — in both directions.
    assertFalse(ModelVisibility.effectiveHidden(stored = "false", defaultHidden = true))
    assertTrue(ModelVisibility.effectiveHidden(stored = "true", defaultHidden = false))
  }
}
