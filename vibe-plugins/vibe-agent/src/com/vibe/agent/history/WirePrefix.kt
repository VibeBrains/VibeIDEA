// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.history

/**
 * How much of one turn's request repeats the previous one — the thing a prompt cache actually pays
 * for, and the thing nobody was measuring.
 *
 * A cache hit is not «the conversation is the same», it is «the request begins with exactly the
 * same bytes». Anything that rewrites an earlier message — dropping images from it, editing it,
 * lifting it to another position — moves the point where the two requests stop matching, and every
 * token after that point is billed as fresh input. On Claude Fable 5.1 a cached token costs 0.025
 * of the base input rate against 0.1 on other Claude models, so the same slip is now four times
 * more expensive to make and worth measuring rather than assuming.
 *
 * Pure and content-based: it compares the wire messages themselves, so the answer does not depend
 * on how they were built or on which provider they are going to.
 */
object WirePrefix {
  /** One wire message reduced to what the cache actually compares: role, text and image identity. */
  data class Line(val role: String, val text: String, val imageDigests: List<String>)

  /**
   * How many leading messages are byte-identical between the previous request and this one.
   *
   * This is the honest measure of «сколько мы переиспользовали»: the first difference ends the
   * match, and everything after it is new input regardless of how familiar it looks.
   */
  fun sharedPrefix(previous: List<Line>, current: List<Line>): Int {
    var shared = 0
    while (shared < previous.size && shared < current.size && previous[shared] == current[shared]) shared++
    return shared
  }

  /**
   * Did this turn keep the conversation append-only?
   *
   * True when the new request begins with the whole of the previous one. False means an earlier
   * message changed — the case worth a word in the log, because it is invisible otherwise and
   * costs money on every turn that follows.
   */
  fun appendOnly(previous: List<Line>, current: List<Line>): Boolean =
    previous.isEmpty() || (current.size >= previous.size && sharedPrefix(previous, current) == previous.size)

  /**
   * Which messages may still carry their images.
   *
   * Images are the most expensive thing in a history and the first thing worth dropping — but
   * dropping them from a message that already travelled REWRITES that message, and the cache stops
   * matching from there on. So the boundary is sticky: it is recomputed only when a new image
   * arrives, and between such turns it stays where it was, leaving the prefix untouched.
   *
   * @param imageMessageIndices indices of messages that carry images, oldest first.
   * @param keep how many of the newest image-bearing messages keep them.
   * @param previousCut the boundary used on the previous turn, or null on the first one.
   * @param newImageArrived whether this turn added a message with images.
   */
  fun imageCut(
    imageMessageIndices: List<Int>,
    keep: Int,
    previousCut: Int?,
    newImageArrived: Boolean,
  ): Int {
    if (imageMessageIndices.isEmpty()) return previousCut ?: 0
    val fresh = imageMessageIndices.takeLast(keep.coerceAtLeast(1)).first()
    // Only ever forward, and only on a turn that brought a new image: a boundary that crept
    // backwards would restore images we already dropped, which rewrites history just as badly.
    if (previousCut == null) return fresh
    return if (newImageArrived) maxOf(previousCut, fresh) else previousCut
  }
}
