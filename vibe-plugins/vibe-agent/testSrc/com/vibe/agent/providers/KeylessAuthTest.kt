// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `"auth": "none"` is a mode of its own, the same in both products of the family:
 * The key goes nowhere on any wire, it is not even looked for, and the provider is usable on any address
 */
class KeylessAuthTest {
  private val none = AuthSpec(AuthSpec.NONE)

  private fun resolved(baseUrl: String, auth: AuthSpec?, key: String? = null, isLocal: Boolean = false) =
    ResolvedProvider(ProviderEntry(id = "p", baseURL = baseUrl, declaredAuth = auth), "openai", baseUrl, key, isLocal)

  @Test
  fun `none lets a server in the local network through without a key`() {
    assertFalse(resolved("http://192.168.1.50:8000/v1", none).missingKey)
  }

  @Test
  fun `localhost passes undeclared, a remote address without a key does not`() {
    assertFalse(resolved("http://localhost:11434/v1", null, isLocal = true).missingKey)
    assertTrue(resolved("https://api.example.com/v1", null).missingKey)
    assertFalse(resolved("https://api.example.com/v1", null, key = "k").missingKey)
  }

  @Test
  fun `none sends the key on no wire`() {
    for (wire in listOf("openai", "anthropic", "gemini", ModelQuirks.WIRE_OPENAI_RESPONSES)) {
      assertEquals(ProviderAuth.Placement(emptyMap(), emptyMap()), ProviderAuth.placement(none, "k", wire), wire)
    }
  }

  @Test
  fun `none is not looked up in any key source`() {
    val entry = ProviderEntry(id = "p", declaredAuth = none, apiKeyEnv = "PATH")
    // PATH is set in every environment, so a lookup would have found it
    assertNull(ApiKeyResolver.resolveQuietly(entry, null))
    assertNull(ApiKeyResolver.resolve(entry, null))
  }

  @Test
  fun `an explicit bearer overrides a seeded none, silence keeps it`() {
    val seed = ProviderEntry(id = "p", baseURL = "http://gpu:8000/v1", declaredAuth = none)
    assertEquals(AuthSpec.BEARER,
                 ProvidersFile.merge(listOf(seed), listOf(ProviderEntry(id = "p", declaredAuth = AuthSpec()))).single().auth.type)
    assertEquals(AuthSpec.NONE, ProvidersFile.merge(listOf(seed), listOf(ProviderEntry(id = "p"))).single().auth.type)
  }

  @Test
  fun `an absent auth is read as silence and an unknown one is warned about`() {
    val warnings = mutableListOf<String>()
    val parsed = ProvidersFile.parse(
      """{"providers":[{"id":"a","baseURL":"https://a/v1"},{"id":"b","baseURL":"https://b/v1","auth":"None"}]}""") { warnings += it }
    assertNull(parsed.single { it.id == "a" }.declaredAuth)
    assertEquals(AuthSpec.BEARER, parsed.single { it.id == "a" }.auth.type)
    assertEquals(1, warnings.size, "warnings=$warnings")
    assertTrue(warnings.single().contains("«None»"), warnings.single())
  }

  @Test
  fun `a key declared next to none is warned about across layers`() {
    val g: Path = Files.createTempDirectory("vibe-keyless-auth-test")
    Files.createDirectories(g.resolve("providers"))
    Files.writeString(g.resolve("providers/local.jsonc"),
                      """{"providers":[{"id":"gpu","baseURL":"http://gpu:8000/v1","auth":"none"}]}""")
    Files.writeString(g.resolve("providers.json"), """{"providers":[{"id":"gpu","apiKeyEnv":"GPU_KEY"}]}""")
    val warnings = mutableListOf<String>()
    val out = ProvidersService.loadFrom(g, null) { warnings += it }
    assertEquals(AuthSpec.NONE, out.single().auth.type)
    assertEquals(1, warnings.size, "warnings=$warnings")
    assertTrue(warnings.single().contains("GPU_KEY"), warnings.single())
  }
}
