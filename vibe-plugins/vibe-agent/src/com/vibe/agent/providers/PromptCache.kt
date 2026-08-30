// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

/**
 * Prompt caching: paying once for the part of the context that does not change.
 *
 * In a long conversation the expensive part is the beginning — project rules, the knowledge index,
 * the files that were attached — and it is re-sent on every turn. Anthropic bills a cached prefix at
 * a fraction of the price, so marking the right boundary turns a repeated cost into a one-off one.
 *
 * The boundary is the whole decision, and it is only worth marking where the prefix is BIG and
 * STABLE: a cache mark on a short system prompt buys nothing and costs a write, and a mark placed
 * after something that changes every turn caches a prefix that never repeats.
 */
object PromptCache {
  /** Below this the cached prefix is not worth its own bookkeeping. */
  const val MIN_CACHEABLE_CHARS = 2_000

  fun shouldCacheSystem(system: String): Boolean = system.length >= MIN_CACHEABLE_CHARS

  /**
   * Which message ends the stable prefix, or null when there is nothing worth caching.
   *
   * The prefix ends at the LAST message that is guaranteed to repeat: everything before the final
   * user message. The final one is what changed, so including it would produce a cache entry used
   * exactly once — the worst of both worlds, since writing a cache entry is not free.
   */
  fun cacheBoundary(messages: List<ChatMessage>, minChars: Int = MIN_CACHEABLE_CHARS): Int? {
    if (messages.size < 2) return null
    val boundary = messages.indexOfLast { it.role == "user" }.takeIf { it > 0 } ?: return null
    val prefixChars = messages.take(boundary).sumOf { it.text.length }
    return if (prefixChars >= minChars) boundary - 1 else null
  }
}
