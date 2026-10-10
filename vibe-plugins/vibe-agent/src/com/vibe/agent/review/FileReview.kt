// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.review

/**
 * The review of one file with no editor in it: the baseline, the text now, the hunks between them and the reader's place
 *
 * Every way of resolving a hunk (the button on it, a key, the bar) goes through [accept] and [reject], and both leave the place
 * on the hunk that followed the resolved one, so no path steps once more after the list has already moved
 * The model changes first and the editor and the journal are told after, so their echo finds nothing left to change
 */
class FileReview(before: String, current: String) {
  var before: String = before
    private set
  var current: String = current
    private set
  var hunks: List<Hunk> = EditHunks.between(before, current)
    private set

  /** The reader's place in [hunks]; null when there is nothing to review */
  var place: Int? = HunkNavigation.clamp(null, hunks.size)
    private set

  val size: Int get() = hunks.size

  /** The text in the editor changed; true when the hunks are not the same as before */
  fun updateCurrent(text: String): Boolean {
    if (text == current) return false
    current = text
    return recompute()
  }

  /** The baseline changed under us (the journal says so); true when the hunks are not the same as before */
  fun updateBefore(text: String): Boolean {
    if (text == before) return false
    before = text
    return recompute()
  }

  /** New edits came from the agent: the review starts over from the first hunk */
  fun restart() {
    place = HunkNavigation.clamp(null, hunks.size)
  }

  fun select(idx: Int) {
    if (idx in hunks.indices) place = idx
  }

  /** One hunk forward or back, round the end of the list */
  fun step(forward: Boolean) {
    place = HunkNavigation.step(place, hunks.size, forward)
  }

  /**
   * Accepts the hunk at [idx]: its lines become the baseline
   * Returns the new baseline for the journal, or null when there is no such hunk
   */
  fun accept(idx: Int): String? {
    val hunk = hunks.getOrNull(idx) ?: return null
    before = EditHunks.accepted(before, current, hunk)
    settle(idx, EditHunks.afterAccept(hunks, idx))
    return before
  }

  /**
   * Rejects the hunk at [idx]: the baseline's lines come back into the text
   * Returns the new text for the document, or null when there is no such hunk
   */
  fun reject(idx: Int): String? {
    val hunk = hunks.getOrNull(idx) ?: return null
    current = EditHunks.rejected(before, current, hunk)
    settle(idx, EditHunks.afterReject(hunks, idx))
    return current
  }

  fun acceptCurrent(): String? = place?.let { accept(it) }

  fun rejectCurrent(): String? = place?.let { reject(it) }

  /** The others keep their shape: only a change that comes from outside (typing, the agent) is diffed again */
  private fun settle(resolvedIdx: Int, left: List<Hunk>) {
    hunks = left
    place = HunkNavigation.afterResolve(resolvedIdx, left.size)
  }

  private fun recompute(): Boolean {
    val fresh = EditHunks.between(before, current)
    val changed = fresh != hunks
    hunks = fresh
    place = HunkNavigation.clamp(place, fresh.size)
    return changed
  }
}
