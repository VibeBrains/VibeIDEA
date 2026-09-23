// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.nav

import java.util.concurrent.CompletableFuture

/**
 * What a language server answered about one position in a file, and the questions about it still in flight.
 *
 * With the modifier held, the platform asks about the position under the mouse on every mouse move — hundreds of
 * times over one word. Without this cache each of those questions would be one more request to the server.
 *
 * Three rules carry the feature, and each breaks it in its own way:
 * - an answer does not outlive an edit of the document: after a change above it, offset 412 is another place;
 * - a question is sent once per position and version: whoever asks next joins the request already in flight;
 * - a failed request leaves no trace: a server that has not started yet answers the next question, while a
 *   remembered failure would leave the position unanswered for the rest of the session.
 *
 * Pure: no platform and no threads of its own, so the rules are tested without an IDE.
 */
class LspAnswerCache<V : Any>(
  private val capacity: Int = DEFAULT_CAPACITY,
  private val ttlMs: Long = DEFAULT_TTL_MS,
  private val now: () -> Long = System::currentTimeMillis,
) {
  /**
   * A position in a file.
   *
   * The document version lives in the entry, not in the key: an answer about another version is not a neighbour to
   * look up but a stale answer to drop.
   */
  data class Key(val uri: String, val offset: Int)

  /** The server's answer: [value], or null when it said there is nothing at this position. */
  data class Known<V : Any>(val value: V?)

  private class Entry<V : Any>(val value: V?, val at: Long, val stamp: Long)

  private class Flight<V : Any>(val stamp: Long, val future: CompletableFuture<V?>)

  private val entries = LinkedHashMap<Key, Entry<V>>(16, 0.75f, true)
  private val flights = HashMap<Key, Flight<V>>()

  /** The answer about this version of the document; null when there is none — never asked, expired, or edited since. */
  @Synchronized
  fun known(key: Key, stamp: Long): Known<V>? {
    val entry = entries[key] ?: return null
    if (entry.stamp != stamp || now() - entry.at > ttlMs) {
      entries.remove(key)
      return null
    }
    return Known(entry.value)
  }

  /**
   * The question about this position and version: the one already in flight, or a new one sent by [send].
   *
   * The answer lands here when it arrives, whether or not anybody still waits for it — the mouse has usually moved on
   * by then, and it comes back.
   */
  fun request(key: Key, stamp: Long, send: () -> CompletableFuture<V?>): CompletableFuture<V?> {
    val flight = CompletableFuture<V?>()
    synchronized(this) {
      flights[key]?.takeIf { it.stamp == stamp }?.let { return it.future }
      flights[key] = Flight(stamp, flight)
    }
    flight.whenComplete { value, error -> settle(key, stamp, flight, value, error) }
    val sent = try {
      send()
    }
    catch (e: RuntimeException) {
      flight.completeExceptionally(e)
      return flight
    }
    sent.whenComplete { value, error -> if (error != null) flight.completeExceptionally(error) else flight.complete(value) }
    return flight
  }

  @Synchronized
  private fun settle(key: Key, stamp: Long, flight: CompletableFuture<V?>, value: V?, error: Throwable?) {
    if (flights[key]?.future === flight) flights.remove(key)
    if (error != null) return
    // A late answer about an older version must not replace one about a newer version: stamps only grow.
    val existing = entries[key]
    if (existing != null && existing.stamp > stamp) return
    entries[key] = Entry(value, now(), stamp)
    while (entries.size > capacity) {
      val oldest = entries.keys.firstOrNull() ?: break
      entries.remove(oldest)
    }
  }

  @Synchronized
  fun size(): Int = entries.size

  companion object {
    /**
     * Positions remembered. A thousand is about an hour of work in one file; more is pointless, because an answer
     * expires by time and by edit anyway.
     */
    const val DEFAULT_CAPACITY = 1_000

    /**
     * How long an answer lives: about as long as a person looks at one screen of code without touching it. Longer,
     * and the cache starts answering for code the server no longer sees the same way — a file saved elsewhere, a
     * dependency installed.
     */
    const val DEFAULT_TTL_MS = 30_000L
  }
}
