// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.providers

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelCatalogCacheTest {
  private val zai = ProviderEntry(id = "zai", baseURL = "https://api.z.ai/v1", models = listOf(ModelEntry("glm-5")))

  private fun entry(vararg ids: String, fingerprint: String = ModelCatalogCache.fingerprint(zai), at: Long = 0L) =
    ModelCatalogCache.Entry(fingerprint, ids.toList(), at)

  @Test
  fun `cached ids are added, hand-declared models are kept`() {
    val merged = ModelCatalogCache.merge(listOf(zai), mapOf("zai" to entry("glm-5", "glm-4.7")))
    assertEquals(listOf("glm-5", "glm-4.7"), merged.single().models.map { it.id })
  }

  @Test
  fun `a refreshed catalog names the models the previous one did not have`() {
    assertEquals(listOf("glm-5.3"), ModelCatalogCache.newModels(entry("glm-5", "glm-4.7"), entry("glm-5", "glm-5.3", "glm-4.7")))
    assertEquals(emptyList(), ModelCatalogCache.newModels(entry("glm-5"), entry("glm-5")))
  }

  @Test
  fun `a first fetch and a moved endpoint name nothing`() {
    assertEquals(emptyList(), ModelCatalogCache.newModels(null, entry("glm-5", "glm-4.7")))
    assertEquals(emptyList(), ModelCatalogCache.newModels(entry("glm-5", fingerprint = "https://old/v1|"), entry("glm-5", "glm-4.7")))
  }

  @Test
  fun `the line names a few new models per provider and counts the rest`() {
    assertEquals(null, ModelCatalogCache.newModelsLine(mapOf("Z.ai" to emptyList())))
    val line = ModelCatalogCache.newModelsLine(mapOf("Z.ai" to listOf("a", "b"), "OpenRouter" to (1..8).map { "m$it" }))!!
    assertTrue("Z.ai — a, b" in line, line)
    assertTrue("m5" in line && "m6" !in line, line)
    assertTrue("3" in line, line)
  }

  @Test
  fun `a provider without a cache entry is untouched`() {
    val merged = ModelCatalogCache.merge(listOf(zai), emptyMap())
    assertEquals(listOf("glm-5"), merged.single().models.map { it.id })
  }

  @Test
  fun `an entry from another endpoint is discarded`() {
    val moved = zai.copy(baseURL = "https://proxy.local/v1")
    val merged = ModelCatalogCache.merge(listOf(moved), mapOf("zai" to entry("glm-5", "glm-4.7")))
    assertEquals(listOf("glm-5"), merged.single().models.map { it.id })
  }

  @Test
  fun `the fetch url is part of the fingerprint`() {
    val custom = zai.copy(modelsFetch = ModelsFetch(enabled = true, url = "https://api.z.ai/v1/openai/models"))
    assertTrue(ModelCatalogCache.fingerprint(custom) != ModelCatalogCache.fingerprint(zai))
  }

  @Test
  fun `the catalog's output ceiling reaches fetched models and fills an unstated one`() {
    val anthropic = ProviderEntry(id = "anthropic", baseURL = "https://api.anthropic.com/v1",
                                  models = listOf(ModelEntry("claude-opus-5"), ModelEntry("claude-sonnet-5", maxOutputTokens = 16_000)))
    val cached = ModelCatalogCache.Entry(ModelCatalogCache.fingerprint(anthropic), listOf("claude-opus-5", "claude-sonnet-5", "claude-opus-5-5"), 0L,
                                         maxOutput = mapOf("claude-opus-5" to 128_000, "claude-sonnet-5" to 128_000, "claude-opus-5-5" to 128_000))
    val models = ModelCatalogCache.merge(listOf(anthropic), mapOf("anthropic" to cached)).single().models.associateBy { it.id }
    assertEquals(128_000, models["claude-opus-5"]!!.maxOutputTokens)
    // What a person wrote wins over what the catalog says.
    assertEquals(16_000, models["claude-sonnet-5"]!!.maxOutputTokens)
    assertEquals(128_000, models["claude-opus-5-5"]!!.maxOutputTokens)
  }

  @Test
  fun `the output ceiling survives the round trip, and a cache written before it reads as unstated`() {
    val cache = mapOf("anthropic" to ModelCatalogCache.Entry("fp", listOf("a"), 1L, maxOutput = mapOf("a" to 64_000)))
    assertEquals(cache, ModelCatalogCache.decode(ModelCatalogCache.encode(cache)))
    val old = """{"version":1,"providers":{"anthropic":{"fingerprint":"fp","fetchedAt":1,"models":["a"]}}}"""
    assertTrue(ModelCatalogCache.decode(old)["anthropic"]!!.maxOutput.isEmpty())
  }

  @Test
  fun `encode-decode round trip keeps ids, fingerprint and time`() {
    val cache = mapOf("zai" to entry("a", "b", at = 1_700_000_000_000L))
    assertEquals(cache, ModelCatalogCache.decode(ModelCatalogCache.encode(cache)))
  }

  @Test
  fun `a corrupt cache file yields an empty cache, never an exception`() {
    assertEquals(emptyMap(), ModelCatalogCache.decode("{ not json"))
    assertEquals(emptyMap(), ModelCatalogCache.decode("[]"))
  }

  @Test
  fun `age is spelled in russian plurals`() {
    val min = 60_000L
    assertEquals("только что", ModelCatalogCache.ageText(0, 30_000))
    assertEquals("1 минуту назад", ModelCatalogCache.ageText(0, min))
    assertEquals("3 минуты назад", ModelCatalogCache.ageText(0, 3 * min))
    assertEquals("11 минут назад", ModelCatalogCache.ageText(0, 11 * min))
    assertEquals("2 часа назад", ModelCatalogCache.ageText(0, 120 * min))
    assertEquals("5 дней назад", ModelCatalogCache.ageText(0, 5 * 24 * 60 * min))
  }
}
