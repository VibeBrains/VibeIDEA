// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.providers

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `params` of the shared provider set: a placeholder in a request is either filled or absent,
 * never sent literally — the vendor answers such a request with an error nobody can read.
 */
class ProviderParamsTest {
  private fun provider(params: String, base: String = "https://{region}.example/v1"): ProviderEntry =
    ProvidersFile.parse("""{"providers":[{"id":"p","baseURL":"$base","auth":"none","params":$params}]}""") { }.single()

  @Test
  fun `both spellings of params are read`() {
    val list = provider("""[{"name":"region","default":"us-central1"},{"name":"project"}]""")
    assertEquals(listOf("region", "project"), list.params.map { it.name })
    assertEquals("us-central1", list.params.first().default)
    val objectForm = provider("""{"region":{"label":"Region","default":"eu"},"extra":{"as":"headers","optional":true}}""")
    assertEquals(listOf("region", "extra"), objectForm.params.map { it.name })
    assertEquals("Region", objectForm.params.first().label)
    assertTrue(objectForm.params.last().headers)
    assertTrue(objectForm.params.last().optional)
  }

  @Test
  fun `a required parameter without a value leaves the provider unconfigured`() {
    val entry = provider("""[{"name":"region","default":"us-central1"},{"name":"project"}]""")
    assertNull(ProviderParams.prepare(entry, emptyMap()))
    val warnings = mutableListOf<String>()
    assertNull(ProvidersService.resolve(entry, null, entered = emptyMap()) { warnings += it })
    assertEquals(1, warnings.size, "warnings=$warnings")
    assertTrue(warnings.single().contains("project"), warnings.single())
  }

  @Test
  fun `the entered value wins over the default and fills the address`() {
    val entry = provider("""[{"name":"region","default":"us-central1"},{"name":"project","default":"demo"}]""")
    val prepared = ProviderParams.prepare(entry, mapOf("region" to "europe-west1"))
    assertEquals("https://europe-west1.example/v1", prepared?.baseURL)
    // Nobody entered anything: the address gets the default of the parameter it names (`region`),
    // not the default of the other declared parameter (`project`, "demo").
    assertEquals("https://us-central1.example/v1", ProviderParams.prepare(entry, emptyMap())?.baseURL)
  }

  @Test
  fun `models fetch and header values are substituted too`() {
    val entry = ProvidersFile.parse("""{"providers":[{"id":"p","baseURL":"https://{region}.example/v1","auth":"none",
      "headers":{"X-Project":"{project}"},"query":{"region":"{region}"},
      "models":{"fetch":"https://{region}.example/catalog?project={project}"},
      "params":[{"name":"region","default":"eu"},{"name":"project","default":"demo"}]}]}""") { }.single()
    val prepared = ProviderParams.prepare(entry, emptyMap())!!
    assertEquals("https://eu.example/catalog?project=demo", prepared.modelsFetch?.url)
    assertEquals(mapOf("X-Project" to "demo"), prepared.headers)
    assertEquals(mapOf("region" to "eu"), prepared.query)
  }

  @Test
  fun `an optional value nobody filled drops the header it alone held`() {
    val entry = ProvidersFile.parse("""{"providers":[{"id":"p","baseURL":"https://example/v1","auth":"none",
      "headers":{"X-Token":"{token}","X-Combined":"Bearer {token}"},
      "params":[{"name":"token","optional":true}]}]}""") { }.single()
    val prepared = ProviderParams.prepare(entry, emptyMap())!!
    assertEquals(mapOf("X-Combined" to "Bearer "), prepared.headers)
    // An optional placeholder inside a longer value becomes empty, it does not remove the value
    assertFalse(prepared.headers.containsKey("X-Token"))
    // Filled in, the longer value is sent with the value in it: only the whole-value placeholder
    // was ever eligible for dropping.
    assertEquals(mapOf("X-Token" to "t", "X-Combined" to "Bearer t"),
                 ProviderParams.prepare(entry, mapOf("token" to "t"))!!.headers)
  }

  @Test
  fun `a headers parameter merges its JSON object into headers`() {
    val entry = ProvidersFile.parse("""{"providers":[{"id":"p","baseURL":"https://example/v1","auth":"none",
      "params":{"extra":{"as":"headers","default":"{\"X-From-File\":\"a\"}"}}}]}""") { }.single()
    assertEquals("a", ProviderParams.prepare(entry, emptyMap())!!.headers["X-From-File"])
    val prepared = ProviderParams.prepare(entry, mapOf("extra" to "{\"X-Entered\":\"b\"}"))!!
    assertEquals(mapOf("X-Entered" to "b"), prepared.headers)
  }

  @Test
  fun `braces that are not declared parameters stay as written`() {
    val entry = provider("""[{"name":"region","default":"eu"}]""", base = "https://{region}.example/{tenant}/v1")
    assertEquals("https://eu.example/{tenant}/v1", ProviderParams.prepare(entry, emptyMap())?.baseURL)
  }

  @Test
  fun `params survive a layer that says nothing and are replaced by one that does`() {
    val seed = provider("""[{"name":"region","default":"eu"}]""")
    val kept = ProvidersFile.merge(listOf(seed), listOf(ProviderEntry(id = "p", order = 1))).single()
    assertEquals(listOf("region"), kept.params.map { it.name })
    val replaced = ProvidersFile.merge(listOf(seed), listOf(ProviderEntry(id = "p", params = listOf(ProviderParam("other")))))
      .single()
    assertEquals(listOf("other"), replaced.params.map { it.name })
  }

  @Test
  fun `the settings key is per provider and parameter`() {
    assertEquals("vibe.providers.params.google-vertex.region", ProviderParamStore.key("google-vertex", "region"))
  }
}
