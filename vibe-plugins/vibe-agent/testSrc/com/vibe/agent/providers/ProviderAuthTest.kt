// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where the key goes, the rule shared with VibeIDE:
 * A declared auth is sent as written, an undeclared one takes the wire's own header
 */
class ProviderAuthTest {
  private val openai = ModelQuirks.WIRE_OPENAI
  private val anthropic = ModelQuirks.WIRE_ANTHROPIC
  private val gemini = "gemini"

  private fun headers(auth: AuthSpec?, wire: String) = ProviderAuth.placement(auth, "k", wire).headers
  private fun query(auth: AuthSpec?, wire: String) = ProviderAuth.placement(auth, "k", wire).query

  @Test
  fun `an undeclared auth takes the wire's own header`() {
    assertEquals(mapOf("Authorization" to "Bearer k"), headers(null, openai))
    assertEquals(mapOf("Authorization" to "Bearer k"), headers(null, ModelQuirks.WIRE_OPENAI_RESPONSES))
    assertEquals(mapOf(ProviderAuth.ANTHROPIC_KEY_HEADER to "k"), headers(null, anthropic))
    assertEquals(mapOf(ProviderAuth.GEMINI_KEY_HEADER to "k"), headers(null, gemini))
  }

  @Test
  fun `a declared bearer is a literal Bearer on every wire`() {
    for (wire in listOf(openai, anthropic, gemini)) {
      assertEquals(mapOf("Authorization" to "Bearer k"), headers(AuthSpec(AuthSpec.BEARER), wire), wire)
    }
    // Unknown types are read as bearer, and warned about at load
    assertEquals(mapOf("Authorization" to "Bearer k"), headers(AuthSpec("basic"), anthropic))
  }

  @Test
  fun `a header without a name takes the wire's own name`() {
    assertEquals(mapOf("x-custom" to "k"), headers(AuthSpec(AuthSpec.HEADER, "x-custom"), gemini))
    assertEquals(mapOf(ProviderAuth.GEMINI_KEY_HEADER to "k"), headers(AuthSpec(AuthSpec.HEADER), gemini))
    assertEquals(mapOf(ProviderAuth.ANTHROPIC_KEY_HEADER to "k"), headers(AuthSpec(AuthSpec.HEADER), anthropic))
    assertEquals(mapOf(ProviderAuth.DEFAULT_KEY_HEADER to "k"), headers(AuthSpec(AuthSpec.HEADER), openai))
  }

  @Test
  fun `a query goes as a parameter, key by default`() {
    assertEquals(mapOf("key" to "k"), query(AuthSpec(AuthSpec.QUERY), openai))
    assertEquals(mapOf("token" to "k"), query(AuthSpec(AuthSpec.QUERY, "token"), gemini))
    assertEquals(emptyMap(), headers(AuthSpec(AuthSpec.QUERY), gemini))
  }

  @Test
  fun `nothing is placed without a key or under none`() {
    val nothing = ProviderAuth.Placement(emptyMap(), emptyMap())
    assertEquals(nothing, ProviderAuth.placement(null, null, anthropic))
    for (wire in listOf(openai, anthropic, gemini, ModelQuirks.WIRE_OPENAI_RESPONSES)) {
      assertEquals(nothing, ProviderAuth.placement(AuthSpec(AuthSpec.NONE), "k", wire), wire)
    }
  }

  @Test
  fun `query parameters join an existing query string`() {
    assertEquals("https://h/m?alt=sse&key=a%26b", ProviderAuth.withQuery("https://h/m?alt=sse", mapOf("key" to "a&b")))
    assertEquals("https://h/m?key=k", ProviderAuth.withQuery("https://h/m", mapOf("key" to "k")))
    assertEquals("https://h/m", ProviderAuth.withQuery("https://h/m", emptyMap()))
  }

  @Test
  fun `a declared bearer on the gemini wire is warned about at load`() {
    val g = Files.createTempDirectory("vibe-provider-auth-test")
    Files.writeString(g.resolve("providers.json"), """{"providers":[
      {"id":"g","protocol":"gemini","baseURL":"https://g/v1beta","auth":"bearer"},
      {"id":"m","protocol":"openai","baseURL":"https://m/v1","auth":"bearer","models":{"static":[{"id":"x","protocol":"gemini"}]}},
      {"id":"ok","protocol":"gemini","baseURL":"https://ok/v1beta"},
      {"id":"o","protocol":"openai","baseURL":"https://o/v1","auth":"bearer"}
    ]}""")
    val warnings = mutableListOf<String>()
    ProvidersService.loadFrom(g, null) { warnings += it }
    assertEquals(2, warnings.size, "warnings=$warnings")
    assertTrue(warnings.any { "«g»" in it } && warnings.any { "«m»" in it }, "warnings=$warnings")
  }
}
