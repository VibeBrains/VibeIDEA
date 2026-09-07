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
  /** Пятиминутный кэш — умолчание вендора; часовой дороже в записи и оправдан не всем. */
  const val TTL_5M = "5m"
  const val TTL_1H = "1h"

  /**
   * Бета-заголовок, без которого часовой кэш не включается.
   *
   * Имя с датой — так его назвал вендор; наше «похожее» имя означало бы молчаливый откат к пяти
   * минутам по цене часовой записи.
   */
  const val EXTENDED_TTL_BETA = "extended-cache-ttl-2025-04-11"

  /**
   * Значение `ttl` для маркера, или null — тогда маркер идёт без него (пять минут по умолчанию).
   *
   * Чужое написание не пропускаем: `"1 hour"` в конфиге дало бы отказ вендора на каждом запросе,
   * а неизвестное значение честнее считать несказанным.
   */
  fun ttlOf(raw: String?): String? = when (raw?.trim()?.lowercase()) {
    TTL_1H, "1hour", "hour" -> TTL_1H
    TTL_5M, "5min", "default", null, "" -> null
    else -> null
  }

  /** Нужен ли бета-заголовок для этого запроса. */
  fun needsExtendedBeta(raw: String?): Boolean = ttlOf(raw) == TTL_1H

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
