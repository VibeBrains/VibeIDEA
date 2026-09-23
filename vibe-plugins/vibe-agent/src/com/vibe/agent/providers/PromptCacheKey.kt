// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import java.security.MessageDigest

/**
 * `prompt_cache_key` of a conversation.
 *
 * Providers that route by this key (OpenAI, xAI) send every request carrying it to the server that already holds its
 * cached prefix; without it a request may land on a cold server, and the whole input is billed at the full rate. The
 * key must stay the same over every turn of one conversation: a random key never hits, and one key for everything piles
 * every conversation onto one server, where they evict each other.
 *
 * The thread id is hashed, so the key says nothing about the conversation. The [kind] of request keeps prompts with a
 * different prefix off each other's key. The format is the one VibeIDE sends for the same field of the shared provider
 * seed.
 */
object PromptCacheKey {
  /** Well under the providers' limits; 128 bits of a hash do not collide across one person's threads. */
  private const val HASH_CHARS = 32

  private const val PREFIX = "vibe-"

  fun of(threadId: String, kind: String): String {
    val digest = MessageDigest.getInstance("SHA-1").digest("$threadId\u0000$kind".toByteArray(Charsets.UTF_8))
    return PREFIX + digest.joinToString("") { "%02x".format(it) }.take(HASH_CHARS)
  }

  /**
   * The key that goes into the request: only when the provider [declared] it accepts one. A strict OpenAI-compatible
   * vendor answers 400 to a field it does not know, so an undeclared endpoint never sees it.
   */
  fun sent(declared: Boolean?, key: String?): String? = key?.takeIf { declared == true }
}
