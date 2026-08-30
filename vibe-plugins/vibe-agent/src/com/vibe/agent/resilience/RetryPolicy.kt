// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.resilience

/**
 * Waiting out a provider instead of failing the turn.
 *
 * A rate limit is not an error, it is a queue: the provider is saying «через тридцать секунд». A
 * chat that turns that into a red line has thrown away a turn the user already paid to compose —
 * and the answer to «что делать?» was written in the response headers all along.
 *
 * What must NOT be retried is just as important. A wrong key returns 401 on every attempt, and
 * retrying it three times only makes the wait before the honest message three times longer. So
 * the classification is explicit, and everything unrecognised is treated as fatal: a retry loop
 * on an unknown failure is how a client hammers someone else's server.
 */
object RetryPolicy {
  enum class Kind {
    /** The provider asked to come back later; it usually says when. */
    RATE_LIMIT,
    /** Its side broke, briefly: 5xx, a dropped connection, a read timeout. */
    TRANSIENT,
    /** Nothing about waiting will help: a bad key, a bad request, an unknown model. */
    FATAL,
  }

  const val MAX_ATTEMPTS = 3

  /** Long enough for a real limit window, short enough that a person does not think it hung. */
  const val MAX_DELAY_MS = 60_000L
  const val BASE_DELAY_MS = 2_000L

  fun classify(statusCode: Int?, exception: Throwable? = null): Kind {
    if (statusCode != null) {
      return when {
        statusCode == 429 -> Kind.RATE_LIMIT
        statusCode in 500..599 -> Kind.TRANSIENT
        // 408 is the server saying it waited too long for us — the same class as a timeout.
        statusCode == 408 -> Kind.TRANSIENT
        else -> Kind.FATAL
      }
    }
    // Order matters: SocketTimeoutException IS an InterruptedIOException, so the timeout has to be
    // recognised before the user-stop rule below, or every read timeout would be called a stop.
    return when {
      exception is java.net.SocketTimeoutException -> Kind.TRANSIENT
      // A user stop must never be mistaken for a network hiccup and retried behind their back.
      exception is java.io.InterruptedIOException -> Kind.FATAL
      exception is java.net.ConnectException -> Kind.TRANSIENT
      exception is java.io.IOException -> Kind.TRANSIENT
      else -> Kind.FATAL
    }
  }

  /**
   * How long to wait before attempt [attempt] (1-based).
   *
   * [retryAfterSeconds] from the response wins: the provider knows its own window, and guessing
   * shorter means being refused again, guessing longer means idling. Without it — a deterministic
   * doubling, deterministic because a backoff nobody can test is a backoff nobody trusts.
   */
  fun delayMs(attempt: Int, kind: Kind, retryAfterSeconds: Long? = null): Long {
    if (kind == Kind.FATAL) return 0
    retryAfterSeconds?.let { return (it * 1000).coerceIn(0, MAX_DELAY_MS) }
    val doubled = BASE_DELAY_MS shl (attempt - 1).coerceIn(0, 10)
    return doubled.coerceAtMost(MAX_DELAY_MS)
  }

  fun shouldRetry(kind: Kind, attempt: Int, maxAttempts: Int = MAX_ATTEMPTS): Boolean =
    kind != Kind.FATAL && attempt < maxAttempts

  /** `Retry-After` is either seconds or an HTTP date; only the seconds form is worth honouring. */
  fun retryAfterSeconds(header: String?): Long? = header?.trim()?.toLongOrNull()?.coerceAtLeast(0)

  /** HTTP status out of a message like `HTTP 429: ...` — the shape our client throws. */
  fun statusFromMessage(message: String?): Int? =
    message?.let { Regex("HTTP (\\d{3})").find(it)?.groupValues?.get(1)?.toIntOrNull() }
}
