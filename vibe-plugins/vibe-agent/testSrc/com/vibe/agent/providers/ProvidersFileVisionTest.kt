// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ProvidersFileVisionTest {
  private fun parseOne(modelJson: String): ModelEntry {
    val text = """{"version":1,"providers":[{"id":"p","models":{"static":[$modelJson]}}]}"""
    val entries = ProvidersFile.parse(text) { fail("unexpected warning: $it") }
    return entries.single().models.single()
  }

  @Test
  fun `vision true is parsed`() {
    assertEquals(true, parseOne("""{"id":"m","vision":true}""").vision)
  }

  @Test
  fun `vision false is parsed`() {
    assertEquals(false, parseOne("""{"id":"m","vision":false}""").vision)
  }

  @Test
  fun `absent vision is null`() {
    assertNull(parseOne("""{"id":"m"}""").vision)
  }

  @Test
  fun `non-boolean vision is treated as unknown`() {
    assertNull(parseOne("""{"id":"m","vision":"yes"}""").vision)
  }

  @Test
  fun `workspace override keeps base vision when unset`() {
    val global = listOf(ProviderEntry(id = "p", models = listOf(ModelEntry(id = "m", vision = true))))
    val workspace = listOf(ProviderEntry(id = "p", models = listOf(ModelEntry(id = "m", temperature = 0.1))))
    val merged = ProvidersFile.merge(global, workspace).single().models.single()
    assertEquals(true, merged.vision)
    assertEquals(0.1, merged.temperature)
  }

  @Test
  fun `workspace explicit false wins over base true`() {
    val global = listOf(ProviderEntry(id = "p", models = listOf(ModelEntry(id = "m", vision = true))))
    val workspace = listOf(ProviderEntry(id = "p", models = listOf(ModelEntry(id = "m", vision = false))))
    assertEquals(false, ProvidersFile.merge(global, workspace).single().models.single().vision)
  }

  @Test
  fun `extends carries vision to the clone`() {
    val entries = listOf(
      ProviderEntry(id = "base", models = listOf(ModelEntry(id = "m", vision = false))),
      ProviderEntry(id = "clone", extendsId = "base", models = listOf(ModelEntry(id = "m", default = true))),
    )
    val resolved = ProvidersFile.resolveExtends(entries) { fail("unexpected warning: $it") }
    val clone = resolved.first { it.id == "clone" }.models.single()
    assertEquals(false, clone.vision)
    assertTrue(clone.default)
  }
}
