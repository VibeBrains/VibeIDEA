// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalProbeTest {
  private fun provider(id: String, baseUrl: String?) = ProviderEntry(id = id, name = id, baseURL = baseUrl)

  @Test
  fun `an ollama listing is understood`() {
    val models = LocalProbe.parseModels("""{"models":[{"name":"qwen2.5-coder:7b"},{"name":"llama3.2"}]}""")
    assertEquals(listOf("qwen2.5-coder:7b", "llama3.2"), models)
  }

  @Test
  fun `an openai-style listing is understood too`() {
    val models = LocalProbe.parseModels("""{"data":[{"id":"local-model"},{"id":"another"}]}""")
    assertEquals(listOf("local-model", "another"), models)
  }

  @Test
  fun `garbage yields no models rather than an exception`() {
    assertTrue(LocalProbe.parseModels("не json").isEmpty())
    assertTrue(LocalProbe.parseModels("{}").isEmpty())
    assertTrue(LocalProbe.parseModels("""{"models":[{"unexpected":1}]}""").isEmpty())
  }

  @Test
  fun `the same machine written two ways is the same endpoint`() {
    // Иначе тому, кто написал 127.0.0.1, предложили бы завести второй такой же провайдер.
    assertEquals(LocalProbe.authorityOf("http://localhost:11434/v1"), LocalProbe.authorityOf("http://127.0.0.1:11434/v1"))
  }

  @Test
  fun `an already described server is not offered again`() {
    val ollama = LocalProbe.CANDIDATES.first { it.id == "ollama" }
    val configured = listOf(provider("мой-локальный", "http://127.0.0.1:11434/v1"))
    assertTrue(LocalProbe.isConfigured(ollama, configured))
  }

  @Test
  fun `matching is by host and port, not by the name someone chose`() {
    val ollama = LocalProbe.CANDIDATES.first { it.id == "ollama" }
    assertFalse(LocalProbe.isConfigured(ollama, listOf(provider("ollama", "https://api.example.com/v1"))))
  }

  @Test
  fun `a provider without a base url does not match anything`() {
    val ollama = LocalProbe.CANDIDATES.first { it.id == "ollama" }
    assertFalse(LocalProbe.isConfigured(ollama, listOf(provider("empty", null))))
    assertNull(LocalProbe.authorityOf(""))
  }

  @Test
  fun `the suggested entry is valid json with the models found`() {
    val found = LocalProbe.Found(LocalProbe.CANDIDATES.first(), listOf("qwen2.5-coder:7b"))
    val json = LocalProbe.suggestedJson(found)
    val parsed = kotlinx.serialization.json.Json.parseToJsonElement(json)
    assertTrue(json.contains("qwen2.5-coder:7b"))
    assertTrue(json.contains("\"type\": \"none\""), "локальный сервер не требует ключа")
    assertTrue(parsed is kotlinx.serialization.json.JsonObject)
  }

  @Test
  fun `the suggestion does not list a hundred models`() {
    val found = LocalProbe.Found(LocalProbe.CANDIDATES.first(), (1..50).map { "model$it" })
    val json = LocalProbe.suggestedJson(found)
    assertEquals(LocalProbe.MAX_SUGGESTED_MODELS, Regex("\\{ \"id\"").findAll(json).count())
  }
}
