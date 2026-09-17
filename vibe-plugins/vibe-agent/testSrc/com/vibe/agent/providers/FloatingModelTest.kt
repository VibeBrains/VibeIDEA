// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `floating`: an id the vendor re-points at another model without notice — declared by hand, or read from the catalog. */
class FloatingModelTest {
  @Test
  fun `the field is read, survives the layers and extends`() {
    val base = ProvidersFile.parse(
      """{"providers":[{"id":"or","baseURL":"https://x/v1","models":{"static":[
         {"id":"~vendor/model-latest","floating":true},{"id":"vendor/model-20260911"}]}}]}""", "global") { }
    val over = ProvidersFile.parse("""{"providers":[{"id":"or","models":{"static":[{"id":"~vendor/model-latest","note":"мой"}]}}]}""", "project") { }
    val merged = ProvidersFile.merge(base, over).single()
    assertEquals(true, merged.models.first { it.id == "~vendor/model-latest" }.floating, "слой без поля не стирает пометку")
    assertEquals("мой", merged.models.first { it.id == "~vendor/model-latest" }.note)
    assertNull(merged.models.first { it.id == "vendor/model-20260911" }.floating, "молчание — не «закреплён»")

    val clone = ProvidersFile.parse("""{"providers":[{"id":"or2","extends":"or","protocol":"anthropic"}]}""", "p") { }
    val resolved = ProvidersFile.resolveExtends(base + clone) { }
    assertEquals(true, resolved.first { it.id == "or2" }.models.first { it.id == "~vendor/model-latest" }.floating)
  }

  // The shape OpenRouter's catalog carries (checked by VibeIDE 12.09.2026): an explicit alias, and a dated snapshot
  // behind a plain name — only the first is a floating promise.
  private val catalog = Json.parseToJsonElement("""
    {"data":[
      {"id":"~vendor/model-latest","alias_target":{"slug":"vendor/model-20260911"}},
      {"id":"vendor/model","canonical_slug":"vendor/model-20260911"},
      {"id":"vendor/model-20260911"},
      {"id":"self","alias_target":{"slug":"self"}}
    ]}""").jsonObject

  @Test
  fun `the catalog marks only an explicit alias`() {
    val models = CatalogModel.parse(catalog).associateBy { it.id }
    assertEquals(true, models["~vendor/model-latest"]!!.floating)
    assertNull(models["vendor/model"]!!.floating, "датированный снимок за простым именем — не плавающий алиас")
    assertNull(models["vendor/model-20260911"]!!.floating)
    assertNull(models["self"]!!.floating, "алиас на себя ничего не обещает")
  }

  @Test
  fun `the catalog fills in what the file does not say, and never overrides it`() {
    val provider = ProvidersFile.parse(
      """{"providers":[{"id":"or","baseURL":"https://x/v1","models":{"static":[
         {"id":"~vendor/model-latest"},{"id":"hand","floating":false}]}}]}""", "t") { }
    val models = CatalogModel.parse(catalog) + CatalogModel("hand", floating = true)
    val entry = ModelCatalogCache.entryOf(provider.single(), models, fetchedAtMs = 1)
    val merged = ModelCatalogCache.merge(provider, mapOf("or" to entry)).single()
    assertEquals(true, merged.models.first { it.id == "~vendor/model-latest" }.floating)
    assertEquals(false, merged.models.first { it.id == "hand" }.floating, "написанное человеком сильнее каталога")
    // Models the file does not declare come from the catalog with the flag as the catalog gave it.
    assertEquals(true, merged.models.first { it.id == "vendor/model-20260911" }.floating == null)
    assertTrue(merged.models.any { it.id == "vendor/model" })
  }

  @Test
  fun `the flag survives the cache file, and a cache written before it reads as silence`() {
    val entry = ModelCatalogCache.Entry("f", listOf("a", "b"), 7, mapOf("a" to true), setOf("b"))
    val back = ModelCatalogCache.decode(ModelCatalogCache.encode(mapOf("or" to entry)))["or"]!!
    assertEquals(entry, back)
    val old = ModelCatalogCache.decode("""{"version":1,"providers":{"or":{"fingerprint":"f","fetchedAt":7,"models":["a"]}}}""")
    assertEquals(emptySet(), old["or"]!!.floating)
  }
}
