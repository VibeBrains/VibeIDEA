// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * `prompt_cache_key`: the same over one conversation, different between conversations, and sent only where declared.
 */
class PromptCacheKeyTest {
  @Test
  fun `the key is VibeIDE's format - a prefix and 32 characters of SHA-1 of thread and kind`() {
    // The expected value is computed outside Kotlin: sha1("thread-1\u0000agent")[:32].
    assertEquals("vibe-19e8ff2fbbd22367d0d6bae9f6e8447d", PromptCacheKey.of("thread-1", "agent"))
  }

  @Test
  fun `one conversation keeps its key, another conversation and another kind get their own`() {
    assertEquals(PromptCacheKey.of("t", "agent"), PromptCacheKey.of("t", "agent"))
    assertNotEquals(PromptCacheKey.of("t", "agent"), PromptCacheKey.of("u", "agent"))
    assertNotEquals(PromptCacheKey.of("t", "agent"), PromptCacheKey.of("t", "plan"))
  }

  @Test
  fun `the key is sent only to a provider that declared it`() {
    assertEquals("k", PromptCacheKey.sent(declared = true, key = "k"))
    assertNull(PromptCacheKey.sent(declared = null, key = "k"), "undeclared: a strict vendor answers 400")
    assertNull(PromptCacheKey.sent(declared = false, key = "k"))
    assertNull(PromptCacheKey.sent(declared = true, key = null), "no conversation, no key")
  }

  @Test
  fun `the declaration is read from the file and survives a layer that does not mention it`() {
    val text = """{"version":1,"providers":[{"id":"xai","promptCacheKey":true},{"id":"plain"}]}"""
    val parsed = ProvidersFile.parse(text) { fail("unexpected warning: $it") }
    assertEquals(true, parsed.first { it.id == "xai" }.promptCacheKey)
    assertNull(parsed.first { it.id == "plain" }.promptCacheKey)
    val merged = ProvidersFile.merge(parsed, listOf(ProviderEntry(id = "xai", timeoutMs = 5_000)))
    assertEquals(true, merged.first { it.id == "xai" }.promptCacheKey, "a workspace layer about timeouts must not drop it")
    val off = ProvidersFile.merge(parsed, listOf(ProviderEntry(id = "xai", promptCacheKey = false)))
    assertEquals(false, off.first { it.id == "xai" }.promptCacheKey, "an explicit false in a layer turns it off")
  }
}
