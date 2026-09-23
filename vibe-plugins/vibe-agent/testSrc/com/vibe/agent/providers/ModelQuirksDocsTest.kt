// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every quirk the catalogue knows is described where a person reads it: the spec and the sample seeded into `.vibe`.
 *
 * Checked on the SHIPPED bytes — the help copy of the spec and the seed resource — because that is what a person and a
 * model are handed. Without it the two lists age separately from the catalogue, and a file written from either cannot
 * use what the IDE already understands.
 */
class ModelQuirksDocsTest {
  private fun shipped(path: String): String =
    checkNotNull(javaClass.getResourceAsStream(path)) { "not shipped: $path" }.bufferedReader().use { it.readText() }

  @Test
  fun `every quirk is described in the spec`() {
    val spec = shipped("/help/manuals/modelQuirksSpec.md")
    for (quirk in ModelQuirks.Quirk.entries) {
      assertTrue("`${quirk.name}`" in spec, "modelQuirksSpec.md does not describe ${quirk.name}")
    }
  }

  @Test
  fun `every quirk is listed in the sample seeded into the project`() {
    val sample = shipped("/vibeDefaults/modelQuirks.json")
    for (quirk in ModelQuirks.Quirk.entries) {
      assertTrue(Regex("\\b${quirk.name}\\b").containsMatchIn(sample), "the modelQuirks.json sample does not list ${quirk.name}")
    }
  }
}
