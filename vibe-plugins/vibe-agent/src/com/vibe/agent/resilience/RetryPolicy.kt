// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
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
    /**
     * This provider will not serve this model — but another one might.
     *
     * Kept apart from FATAL because the two demand opposite answers. A bad key is wrong at the next
     * provider too, so switching there would hide the real message behind an unrelated second
     * failure. Access that has been revoked, run out or was never granted is exactly what another
     * provider fixes — and it is the shape of an ordinary event: OpenAI announced the end of model
     * access for Cursor on 12.11.2026, and every one of those users met this status at once.
     */
    UNAVAILABLE,
    /** Nothing about waiting will help: a bad key, a bad request, an unknown model. */
    FATAL,
  }

  /** The first request and three repeats: the pauses are 1, 2 and 4 seconds, the way VibeIDE paces them */
  const val MAX_ATTEMPTS = 4

  /** A pause the client makes up itself never exceeds this: longer is the provider's call, and only its header makes it */
  const val MAX_DELAY_MS = 5_000L
  const val BASE_DELAY_MS = 1_000L

  /** A rate limit is not a hiccup: the first repeat waits a little longer, so it does not run into the same window */
  const val RATE_LIMIT_BASE_DELAY_MS = 2_000L

  /**
   * The longest `Retry-After` the client sits out
   *
   * Beyond it the turn stops and says how long the provider asked for
   * Half a minute of a frozen chat is the most a person takes for «it is working», and a longer wait is theirs to decide
   */
  const val MAX_RETRY_AFTER_SECONDS = 30L

  fun classify(statusCode: Int?, exception: Throwable? = null): Kind {
    if (statusCode != null) {
      return when {
        statusCode == 429 -> Kind.RATE_LIMIT
        statusCode in 500..599 -> Kind.TRANSIENT
        // 408 is the server saying it waited too long for us — the same class as a timeout.
        statusCode == 408 -> Kind.TRANSIENT
        // 402 out of credit, 403 access revoked or never granted, 404 no such model here. Waiting
        // changes none of them; another provider changes all three.
        statusCode == 402 || statusCode == 403 || statusCode == 404 -> Kind.UNAVAILABLE
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
   * How long to wait after the failed attempt [attempt] (1-based)
   *
   * The doubling is the floor, deterministic because a backoff nobody can test is a backoff nobody trusts
   * [retryAfterSeconds] from the response lifts it: the provider knows its own window, and guessing shorter means being refused again
   * A header longer than [MAX_RETRY_AFTER_SECONDS] is not waited out here ([verdict] stops first)
   */
  fun delayMs(attempt: Int, kind: Kind, retryAfterSeconds: Long? = null): Long {
    if (kind == Kind.FATAL || kind == Kind.UNAVAILABLE) return 0
    val base = if (kind == Kind.RATE_LIMIT) RATE_LIMIT_BASE_DELAY_MS else BASE_DELAY_MS
    val doubled = (base shl (attempt - 1).coerceIn(0, 10)).coerceAtMost(MAX_DELAY_MS)
    val asked = (retryAfterSeconds ?: 0) * 1000
    return maxOf(doubled, asked.coerceIn(0, MAX_RETRY_AFTER_SECONDS * 1000))
  }

  fun shouldRetry(kind: Kind, attempt: Int, maxAttempts: Int = MAX_ATTEMPTS): Boolean =
    kind != Kind.FATAL && kind != Kind.UNAVAILABLE && attempt < maxAttempts

  /** What to do after a failed attempt */
  sealed interface Verdict {
    /** Sit out [delayMs] and send the request again */
    data class Wait(val delayMs: Long) : Verdict

    /** The provider asked for [retryAfterSeconds], more than the client waits on its own: stop and say so */
    data class TooLong(val retryAfterSeconds: Long) : Verdict

    /** Nothing about waiting helps, or the attempts are used up */
    data object Stop : Verdict
  }

  /**
   * The one decision after attempt [attempt] failed
   *
   * A request that is not worth repeating stops at once
   * Then a `Retry-After` beyond the ceiling stops with its length named, even on the last attempt
   * Because «asked for 45 s» says more than «failed»
   * Only then the count of attempts and the pause
   */
  fun verdict(kind: Kind, attempt: Int, retryAfterSeconds: Long?, maxAttempts: Int = MAX_ATTEMPTS): Verdict = when {
    kind == Kind.FATAL || kind == Kind.UNAVAILABLE -> Verdict.Stop
    retryAfterSeconds != null && retryAfterSeconds > MAX_RETRY_AFTER_SECONDS -> Verdict.TooLong(retryAfterSeconds)
    attempt >= maxAttempts -> Verdict.Stop
    else -> Verdict.Wait(delayMs(attempt, kind, retryAfterSeconds))
  }

  /** `Retry-After` is either seconds or an HTTP date; only the seconds form is worth honouring. */
  fun retryAfterSeconds(header: String?): Long? = header?.trim()?.toLongOrNull()?.coerceAtLeast(0)

  /** HTTP status out of a message like `HTTP 429: ...` — the shape our client throws. */
  fun statusFromMessage(message: String?): Int? =
    message?.let { Regex("HTTP (\\d{3})").find(it)?.groupValues?.get(1)?.toIntOrNull() }
}
