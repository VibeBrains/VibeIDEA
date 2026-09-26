// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

/**
 * Anthropic's credit for a retry after a refusal: the conversation cached for the refused model is not written again
 *
 * Prompt caches are per model, so a retry on the next model of the chain writes the whole prefix to cache anew
 * A refusal carries a one-time token; echoed on the retry, the retry is billed as if the conversation had been there
 * all along (platform.claude.com/docs/en/build-with-claude/fallback-credit, checked 2026-09-26)
 *
 * The retry must match the refused request and carry the same beta headers, so the header goes on every request to
 * Anthropic's own API, not only on the retry; other vendors on this wire never get it
 * Pure: addresses, times and error texts in, decisions out
 */
object FallbackCredit {
  const val BETA = "fallback-credit-2026-07-01"

  /** The request field the token travels in */
  const val FIELD = "fallback_credit_token"

  /** The token expires five minutes after the refusal */
  const val TTL_MS = 5 * 60 * 1000L

  /** The only host the beta is documented for among the addresses a person may give this wire */
  fun offered(baseUrl: String): Boolean = AnthropicApi.official(baseUrl)

  /** A token held for the next attempt: which provider refused, and when */
  data class Credit(val providerId: String, val token: String, val atMs: Long)

  /** The token for a retry on [providerId] at [nowMs], or null: another provider cannot redeem it, an old one expired */
  fun usable(credit: Credit?, providerId: String, nowMs: Long): String? =
    credit?.takeIf { it.providerId == providerId && nowMs - it.atMs < TTL_MS }?.token

  /** What to do when a retry carrying the token failed */
  enum class OnError {
    /** Not about the token: the error stands */
    RAISE,

    /** The vendor could not redeem for a moment: the same request once more */
    REPEAT,

    /** The token or the body match was refused: the retry goes without the token, the credit is forfeited */
    DROP_TOKEN,
  }

  fun onError(message: String?): OnError {
    val text = message.orEmpty()
    return when {
      !text.startsWith(HTTP_400) -> OnError.RAISE
      TRANSIENT in text -> OnError.REPEAT
      FIELD in text || MISMATCH in text -> OnError.DROP_TOKEN
      else -> OnError.RAISE
    }
  }

  private const val HTTP_400 = "HTTP 400"
  private const val TRANSIENT = "redemption temporarily unavailable"
  private const val MISMATCH = "does not match"
}
