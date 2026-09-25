// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * «The address is this machine» and «the models run on this machine» are two questions with two answers
 * The address decides the key; `runsLocally` decides the offline mode, local budgets and the privacy label
 * The field and its meaning are shared with VibeIDE (`providers/README.md` of the set)
 */
class RunsLocallyTest {
  private fun resolved(baseUrl: String, runsLocally: Boolean?, auth: AuthSpec? = null) = ResolvedProvider(
    ProviderEntry(id = "p", baseURL = baseUrl, declaredAuth = auth, runsLocally = runsLocally),
    "openai", baseUrl, apiKey = null, localAddress = LocalAddress.isLocal(baseUrl),
  )

  @Test
  fun `without the field the address decides`() {
    assertTrue(resolved("http://localhost:11434/v1", null).runsLocally)
    assertFalse(resolved("https://api.example.com/v1", null).runsLocally)
  }

  @Test
  fun `a proxy on localhost to a cloud model is not a local model and still needs no key`() {
    val proxy = resolved("http://localhost:4000/v1", runsLocally = false)
    assertFalse(proxy.runsLocally)
    assertFalse(proxy.needsKey)
    assertFalse(proxy.missingKey)
  }

  @Test
  fun `a GPU server of one's own is a local model and is still asked with its key`() {
    val gpu = resolved("http://192.168.1.50:8000/v1", runsLocally = true)
    assertTrue(gpu.runsLocally)
    assertTrue(gpu.needsKey)
    assertTrue(gpu.missingKey)
    // Keyless it becomes only the way any address does: by declaring none
    assertFalse(resolved("http://192.168.1.50:8000/v1", runsLocally = true, auth = AuthSpec(AuthSpec.NONE)).needsKey)
  }

  @Test
  fun `the field is read, and a layer that says nothing about it keeps it`() {
    val seeded = ProvidersFile.parse(
      """{ "providers": [ { "id": "litellm", "baseURL": "http://localhost:4000/v1", "runsLocally": false } ] }""") { }.single()
    assertEquals(false, seeded.runsLocally)
    assertEquals(false, ProvidersFile.merge(listOf(seeded), listOf(ProviderEntry(id = "litellm", order = 1))).single().runsLocally)
    assertEquals(true, ProvidersFile.merge(listOf(seeded), listOf(ProviderEntry(id = "litellm", runsLocally = true))).single().runsLocally)
    assertNull(ProvidersFile.parse("""{ "providers": [ { "id": "ollama" } ] }""") { }.single().runsLocally)
  }
}
