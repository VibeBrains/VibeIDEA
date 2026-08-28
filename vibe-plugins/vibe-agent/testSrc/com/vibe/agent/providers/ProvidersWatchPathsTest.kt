// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProvidersWatchPathsTest {
  private val base = "/work/project"
  private val home = "/Users/dev"

  private fun matches(path: String) = ProvidersWatchPaths.matches(path, base, home)

  @Test
  fun providerConfigsOfBothScopesMatch() {
    assertTrue(matches("$base/.vibe/providers.json"))
    assertTrue(matches("$home/.vibe/providers.json"))
    assertTrue(matches("$base/.vibe/providers/zai.jsonc"))
    assertTrue(matches("$home/.vibe/providers/openrouter.json"))
    assertTrue(matches("$base/.vibe/.env"))
    assertTrue(matches("$home/.vibe/.env"))
    // The providers dir itself appearing/disappearing changes the registry.
    assertTrue(matches("$base/.vibe/providers"))
  }

  @Test
  fun unrelatedPathsDoNot() {
    assertFalse(matches("$base/.vibe/hooks.example.jsonc"))
    assertFalse(matches("$base/.vibe/providers/README.md"))
    assertFalse(matches("$base/.vibe/audit.jsonl"))
    assertFalse(matches("$base/src/providers.json"))
    assertFalse(matches("/elsewhere/.vibe/providers.json"))
    // No project open: only the global scope matches.
    assertTrue(ProvidersWatchPaths.matches("$home/.vibe/providers.json", null, home))
    assertFalse(ProvidersWatchPaths.matches("$base/.vibe/providers.json", null, home))
  }

  @Test
  fun windowsSeparatorsAreNormalized() {
    assertTrue(ProvidersWatchPaths.matches("C:\\Users\\dev\\.vibe\\providers.json", null, "C:\\Users\\dev"))
  }
}
