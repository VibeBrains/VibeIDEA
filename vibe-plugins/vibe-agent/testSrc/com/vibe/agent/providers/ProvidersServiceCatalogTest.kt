// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Registry assembly, weakest layer first: global catalog → project catalog →
 * global `providers.json` → project `providers.json` (the seeded catalog plays the
 * role of built-ins, so it stays UNDER both user files); `active` is tri-state and
 * filtered last so `extends`/same-id patches work against inactive catalog entries
 * without silently flipping their toggle (decision №24).
 */
class ProvidersServiceCatalogTest {
  private fun dir(): Path = Files.createTempDirectory("vibe-providers-catalog-test")

  private fun write(root: Path, rel: String, content: String) {
    val p = root.resolve(rel)
    Files.createDirectories(p.parent)
    Files.writeString(p, content)
  }

  @Test
  fun catalogFilesMergeAlphabeticallyAndProvidersJsonWins() {
    val g = dir()
    write(g, "providers/a.jsonc", """{"providers":[{"id":"p","name":"A name","baseURL":"https://a.example/v1"}]}""")
    write(g, "providers/b.jsonc", """{"providers":[{"id":"p","name":"B name"},{"id":"q","baseURL":"https://q.example/v1"}]}""")
    write(g, "providers.json", """{"providers":[{"id":"q","name":"Q override"}]}""")
    val warnings = mutableListOf<String>()
    val out = ProvidersService.loadFrom(g, null) { warnings.add(it) }
    // b.jsonc overlays a.jsonc field-by-field: the name changes, the baseURL survives.
    assertEquals("B name", out.single { it.id == "p" }.name)
    assertEquals("https://a.example/v1", out.single { it.id == "p" }.baseURL)
    // providers.json overlays the whole catalog.
    assertEquals("Q override", out.single { it.id == "q" }.name)
    assertEquals("https://q.example/v1", out.single { it.id == "q" }.baseURL)
    assertTrue(warnings.isEmpty(), "warnings=$warnings")
  }

  @Test
  fun brokenCatalogFileNeverKillsTheOthers() {
    val g = dir()
    write(g, "providers/bad.jsonc", "{ this is not json")
    write(g, "providers/good.jsonc", """{"providers":[{"id":"ok","baseURL":"https://ok.example/v1"}]}""")
    val warnings = mutableListOf<String>()
    val out = ProvidersService.loadFrom(g, null) { warnings.add(it) }
    assertEquals(listOf("ok"), out.map { it.id })
    assertTrue(warnings.isNotEmpty())
  }

  @Test
  fun extendsAcrossFilesAndFromInactiveBaseResolves() {
    val g = dir()
    write(g, "providers/base.jsonc",
      """{"providers":[{"id":"base","active":false,"baseURL":"https://base.example/v1","apiKeyEnv":"BASE_KEY"}]}""")
    write(g, "providers/clone.jsonc", """{"providers":[{"id":"clone","extends":"base","name":"Clone"}]}""")
    val warnings = mutableListOf<String>()
    val out = ProvidersService.loadFrom(g, null) { warnings.add(it) }
    // The inactive base is merged (extends resolves against it) but not returned.
    assertEquals(listOf("clone"), out.map { it.id })
    assertEquals("https://base.example/v1", out.single().baseURL)
    assertEquals("BASE_KEY", out.single().apiKeyEnv)
    assertTrue(warnings.isEmpty(), "warnings=$warnings")
  }

  @Test
  fun inactiveCatalogEntryActivatesByProvidersJsonOverride() {
    val g = dir()
    write(g, "providers/off.jsonc", """{"providers":[{"id":"off","active":false,"baseURL":"https://off.example/v1"}]}""")
    assertEquals(emptyList(), ProvidersService.loadFrom(g, null) { }.map { it.id })
    // The owner scenario: flip a seeded provider on without touching the catalog file.
    write(g, "providers.json", """{"providers":[{"id":"off","active":true}]}""")
    val out = ProvidersService.loadFrom(g, null) { }
    assertEquals(listOf("off"), out.map { it.id })
    assertEquals("https://off.example/v1", out.single().baseURL)
  }

  @Test
  fun globalProvidersJsonBeatsSeededProjectCatalog() {
    // The regression that mattered: a seeded (inactive) project file must never silence
    // or rewrite the user's live global providers.json entry with the same id.
    val g = dir()
    val p = dir()
    write(g, "providers.json",
      """{"providers":[{"id":"openai","name":"Мой прокси","baseURL":"https://my-proxy.example/v1","apiKeyEnv":"MY_KEY"}]}""")
    write(p, "providers/openai.jsonc", """{"providers":[{"id":"openai","active":false,"baseURL":"https://api.openai.com/v1"}]}""")
    val e = ProvidersService.loadFrom(g, p) { }.single { it.id == "openai" }
    assertEquals("Мой прокси", e.name)
    assertEquals("https://my-proxy.example/v1", e.baseURL)
    assertEquals("MY_KEY", e.apiKeyEnv)
  }

  @Test
  fun entryWithoutActiveIsAliveEvenOverAnInactiveSeed() {
    // Deliberate semantics (not tri-state): an entry without `active` counts as ON, so a
    // user's pre-catalog providers.json keeps working when a seed shares its id. A patch
    // that must NOT activate its target repeats "active": false explicitly (specced).
    val g = dir()
    write(g, "providers/off.jsonc", """{"providers":[{"id":"off","active":false,"baseURL":"https://off.example/v1"}]}""")
    write(g, "providers.json", """{"providers":[{"id":"off","apiKeyEnv":"MY_OFF_KEY"}]}""")
    val out = ProvidersService.loadFrom(g, null) { }
    assertEquals(listOf("off"), out.map { it.id })
    assertEquals("MY_OFF_KEY", out.single().apiKeyEnv)
    // The explicit form stays inactive.
    write(g, "providers.json", """{"providers":[{"id":"off","active":false,"apiKeyEnv":"MY_OFF_KEY"}]}""")
    assertEquals(emptyList(), ProvidersService.loadFrom(g, null) { }.map { it.id })
  }

  @Test
  fun explicitFetchTrueOverridesBaseFetchFalse() {
    val g = dir()
    write(g, "providers/base.jsonc",
      """{"providers":[{"id":"p","baseURL":"https://p.example/v1","models":{"fetch":false}}]}""")
    write(g, "providers.json", """{"providers":[{"id":"p","models":{"fetch":true}}]}""")
    val e = ProvidersService.loadFrom(g, null) { }.single()
    assertEquals(ModelsFetch(enabled = true), e.modelsFetch)
  }

  @Test
  fun projectScopeOverridesGlobalCatalog() {
    val g = dir()
    val p = dir()
    write(g, "providers/zz.jsonc", """{"providers":[{"id":"p1","baseURL":"https://global.example/v1"}]}""")
    write(p, "providers/p1.jsonc", """{"providers":[{"id":"p1","name":"Проектный"}]}""")
    val e = ProvidersService.loadFrom(g, p) { }.single { it.id == "p1" }
    assertEquals("Проектный", e.name)
    assertEquals("https://global.example/v1", e.baseURL)
    assertEquals(ProviderOrigin.OVERRIDDEN, e.origin)
  }

  @Test
  fun providersJsonAloneStillWorksWithoutCatalog() {
    val g = dir()
    write(g, "providers.json", """{"providers":[{"id":"solo","baseURL":"https://solo.example/v1"}]}""")
    val out = ProvidersService.loadFrom(g, null) { }
    assertEquals(listOf("solo"), out.map { it.id })
    assertEquals(ProviderOrigin.GLOBAL, out.single().origin)
  }

  @Test
  fun unresolvedExtendsWarnsOnceOnTheMergedPass() {
    val g = dir()
    write(g, "providers/x.jsonc", """{"providers":[{"id":"x","extends":"missing","baseURL":"https://x.example/v1"}]}""")
    val warnings = mutableListOf<String>()
    val out = ProvidersService.loadFrom(g, null) { warnings.add(it) }
    assertEquals(listOf("x"), out.map { it.id })
    assertEquals(1, warnings.count { "missing" in it }, "warnings=$warnings")
  }
}
