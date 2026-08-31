// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.resilience

/**
 * Where to go when the chosen provider cannot answer.
 *
 * Failover is not retry. Retry waits out the SAME provider because it asked to be waited for;
 * failover gives up on it and asks someone else. Confusing the two produces the worst behaviour of
 * both: hammering a dead endpoint, or abandoning a live one that merely asked for thirty seconds.
 *
 * So the rule is narrow: fall over only when waiting has already failed or cannot help, never on a
 * bad key (the next provider will not fix your key), and never twice to the same place.
 */
object FailoverPlan {
  data class Target(val providerId: String, val modelId: String) {
    override fun toString(): String = providerId + "/" + modelId
  }

  /** Parses `провайдер/модель` entries; malformed ones are dropped by the caller's validation. */
  fun parseChain(spec: String): List<Target> =
    spec.split(',', '\n').mapNotNull { raw ->
      val entry = raw.trim()
      val slash = entry.indexOf('/')
      if (slash <= 0 || slash == entry.length - 1) null
      else Target(entry.substring(0, slash).trim(), entry.substring(slash + 1).trim())
    }

  /**
   * The next target to try, or null when there is nowhere left to go.
   *
   * [tried] includes the original target; a chain that names it again must not send the turn back
   * to the endpoint that just failed.
   */
  fun next(chain: List<Target>, tried: Set<Target>): Target? = chain.firstOrNull { it !in tried }

  /** Only these kinds are worth another provider at all. */
  fun shouldFailOver(kind: RetryPolicy.Kind, retriesExhausted: Boolean): Boolean = when (kind) {
    // A key is wrong here and will be wrong there: failing over hides the real message behind a
    // second, unrelated failure.
    RetryPolicy.Kind.FATAL -> false
    // Access revoked, out of credit, no such model here: waiting cannot help, so we do not wait for
    // the retries to be spent — we go now. This is the one kind where switching providers IS the
    // fix rather than a way of hiding the message.
    RetryPolicy.Kind.UNAVAILABLE -> true
    RetryPolicy.Kind.RATE_LIMIT -> retriesExhausted
    RetryPolicy.Kind.TRANSIENT -> retriesExhausted
  }
}
