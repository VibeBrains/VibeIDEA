// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a catalog says a model accepts reaches the model; what it does not say is not guessed. */
class CatalogModelTest {
  private fun parse(text: String) = CatalogModel.parse(Json.parseToJsonElement(text).jsonObject)

  @Test
  fun `moonshot marks images with supports_image_in`() {
    assertEquals(listOf(CatalogModel("kimi-k3", true), CatalogModel("kimi-k2.7-code", false)), parse("""
      { "data": [ { "id": "kimi-k3", "supports_image_in": true }, { "id": "kimi-k2.7-code", "supports_image_in": false } ] }
    """))
  }

  @Test
  fun `openrouter lists input modalities`() {
    assertEquals(listOf(CatalogModel("a/vision", true), CatalogModel("b/text", false)), parse("""
      { "data": [
        { "id": "a/vision", "architecture": { "input_modalities": ["text", "image"] } },
        { "id": "b/text", "architecture": { "input_modalities": ["text"] } } ] }
    """))
  }

  @Test
  fun `a catalog that says nothing leaves vision unknown, gemini names included`() {
    assertEquals(listOf(CatalogModel("gpt-x", null)), parse("""{ "data": [ { "id": "gpt-x", "object": "model" } ] }"""))
    assertEquals(listOf(CatalogModel("gemini-3-pro", null)), parse("""{ "models": [ { "name": "models/gemini-3-pro" } ] }"""))
  }

  @Test
  fun `the flags reach models through the cache, and what a person wrote wins`() {
    val provider = ProviderEntry(id = "kimi", baseURL = "https://api.moonshot.ai/v1",
                                 models = listOf(ModelEntry("kimi-k3"), ModelEntry("kimi-k2.7-code", vision = true)))
    val entry = ModelCatalogCache.entryOf(provider, listOf(
      CatalogModel("kimi-k3", true), CatalogModel("kimi-k2.7-code", false), CatalogModel("kimi-k2.6", false), CatalogModel("x")), 0L)
    val cache = ModelCatalogCache.decode(ModelCatalogCache.encode(mapOf("kimi" to entry)))
    assertEquals(mapOf("kimi-k3" to true, "kimi-k2.7-code" to false, "kimi-k2.6" to false), cache.getValue("kimi").vision)

    val models = ModelCatalogCache.merge(listOf(provider), cache).single().models.associateBy { it.id }
    assertEquals(true, models.getValue("kimi-k3").vision, "запись без vision берёт слово каталога")
    assertEquals(true, models.getValue("kimi-k2.7-code").vision, "что написал человек — сильнее каталога")
    assertEquals(false, models.getValue("kimi-k2.6").vision)
    assertNull(models.getValue("x").vision)
  }

  @Test
  fun `a cache written before the flags existed still reads`() {
    val old = """{ "version": 1, "providers": { "zai": { "fingerprint": "f", "fetchedAt": 1, "models": ["glm-5"] } } }"""
    assertEquals(emptyMap(), ModelCatalogCache.decode(old).getValue("zai").vision)
  }
}
