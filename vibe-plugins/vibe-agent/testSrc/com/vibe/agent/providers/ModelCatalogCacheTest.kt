// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
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
