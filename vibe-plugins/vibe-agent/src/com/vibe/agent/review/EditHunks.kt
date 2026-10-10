// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.review

import com.intellij.diff.comparison.ByLineRt
import com.intellij.diff.comparison.CancellationChecker
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.comparison.DiffTooBigException

/**
 * One change of a file: the lines it covers in the baseline text and in the current text
 *
 * Both ranges are half-open and count lines the way a document does: a text that ends with a newline has a last empty line
 * An empty baseline range is an addition, an empty current range is a removal, two non-empty ranges are a replacement
 */
data class Hunk(val beforeStart: Int, val beforeEnd: Int, val afterStart: Int, val afterEnd: Int) {
  init {
    require(0 <= beforeStart && beforeStart <= beforeEnd && 0 <= afterStart && afterStart <= afterEnd) { "bad hunk $this" }
    require(beforeStart != beforeEnd || afterStart != afterEnd) { "empty hunk $this" }
  }

  val kind: Kind
    get() = when {
      beforeStart == beforeEnd -> Kind.ADDED
      afterStart == afterEnd -> Kind.REMOVED
      else -> Kind.CHANGED
    }

  enum class Kind { ADDED, REMOVED, CHANGED }
}

/** A replacement of `[start, end)` of the old text with [replacement] */
data class TextEdit(val start: Int, val end: Int, val replacement: String)

/**
 * The line arithmetic of a review: which hunks a file has against its baseline, and the texts that accepting or rejecting one gives
 *
 * Pure: strings in, strings out, no editor and no application
 * The comparison is the platform's own line comparison that the diff viewer uses (whitespace is a change, as it is for a reader)
 * Separators are normalized to `\n` for the comparison, because a document never holds anything else
 */
object EditHunks {
  fun normalize(text: String): String = if ('\r' in text) text.replace("\r\n", "\n").replace('\r', '\n') else text

  /** The lines of a text as a document counts them: `n` separators make `n + 1` lines */
  fun lines(text: String): List<String> = normalize(text).split('\n')

  /**
   * The hunks of [current] against [before], top to bottom
   *
   * A pair of texts too big for the comparison becomes one hunk over everything:
   * the file can still be accepted or rejected whole, which beats a review that cannot start
   */
  fun between(before: String, current: String): List<Hunk> {
    val old = lines(before)
    val now = lines(current)
    if (old == now) return emptyList()
    return try {
      ByLineRt.compare(old, now, ComparisonPolicy.DEFAULT, CancellationChecker.EMPTY)
        .iterateChanges()
        .map { Hunk(it.start1, it.end1, it.start2, it.end2) }
    }
    catch (_: DiffTooBigException) {
      listOf(Hunk(0, old.size, 0, now.size))
    }
  }

  /** The old lines a hunk replaced or removed */
  fun removedLines(before: String, hunk: Hunk): List<String> = lines(before).subList(hunk.beforeStart, hunk.beforeEnd)

  /**
   * The new baseline once [hunk] is accepted: the baseline with the hunk's current lines written in
   * After it the hunk is no longer a difference, and the other hunks stay as they were
   * The separators of the old baseline are kept, so a file with CRLF is not turned into LF by a review
   */
  fun accepted(before: String, current: String, hunk: Hunk): String {
    val old = lines(before)
    val now = lines(current)
    requireFits(hunk, old, now)
    val merged = old.subList(0, hunk.beforeStart) + now.subList(hunk.afterStart, hunk.afterEnd) + old.subList(hunk.beforeEnd, old.size)
    return likeOriginal(before, merged.joinToString("\n"))
  }

  /** The new current text once [hunk] is rejected: the current text with the hunk's lines replaced by the baseline's */
  fun rejected(before: String, current: String, hunk: Hunk): String {
    val old = lines(before)
    val now = lines(current)
    requireFits(hunk, old, now)
    val merged = now.subList(0, hunk.afterStart) + old.subList(hunk.beforeStart, hunk.beforeEnd) + now.subList(hunk.afterEnd, now.size)
    return merged.joinToString("\n")
  }

  /**
   * The hunks that are left once the one at [idx] is accepted: the others keep their shape, and the baseline lines after it move
   *
   * They are not diffed again, because a new diff of the same change may cut it differently
   * (equal lines repeat: braces, blanks) and the reader would watch hunks split or merge under their hands
   */
  fun afterAccept(hunks: List<Hunk>, idx: Int): List<Hunk> {
    val gone = hunks[idx]
    val shift = (gone.afterEnd - gone.afterStart) - (gone.beforeEnd - gone.beforeStart)
    return hunks.mapIndexedNotNull { i, hunk ->
      when {
        i < idx -> hunk
        i == idx -> null
        else -> hunk.copy(beforeStart = hunk.beforeStart + shift, beforeEnd = hunk.beforeEnd + shift)
      }
    }
  }

  /** The hunks that are left once the one at [idx] is rejected: the current lines after it move, nothing else */
  fun afterReject(hunks: List<Hunk>, idx: Int): List<Hunk> {
    val gone = hunks[idx]
    val shift = (gone.beforeEnd - gone.beforeStart) - (gone.afterEnd - gone.afterStart)
    return hunks.mapIndexedNotNull { i, hunk ->
      when {
        i < idx -> hunk
        i == idx -> null
        else -> hunk.copy(afterStart = hunk.afterStart + shift, afterEnd = hunk.afterEnd + shift)
      }
    }
  }

  /** [text] (LF) written with the line separator of [original]: CRLF if [original] has it, else unchanged */
  fun likeOriginal(original: String, text: String): String = if ("\r\n" in original) text.replace("\n", "\r\n") else text

  /** The removed lines as the editor shows them, and how many more there were */
  data class Preview(val lines: List<String>, val hidden: Int)

  /** At most [cap] of the removed lines, with tabs spelled out as [tabSize] spaces, and the count of those that did not fit */
  fun preview(removed: List<String>, cap: Int, tabSize: Int): Preview {
    val spaces = " ".repeat(tabSize.coerceAtLeast(1))
    return Preview(removed.take(cap).map { it.replace("\t", spaces) }, (removed.size - cap).coerceAtLeast(0))
  }

  /**
   * The smallest edit that turns [old] into [new], or null when they are the same
   *
   * A rejected hunk is written to the document as this edit rather than as a new text:
   * the caret, folds and undo entry then touch only the lines that changed
   */
  fun minimalEdit(old: String, new: String): TextEdit? {
    if (old == new) return null
    val limit = minOf(old.length, new.length)
    var prefix = 0
    while (prefix < limit && old[prefix] == new[prefix]) prefix++
    // A surrogate pair is one character: the edit must not start between its halves
    if (prefix > 0 && Character.isHighSurrogate(old[prefix - 1])) prefix--
    var suffix = 0
    while (suffix < limit - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
    if (suffix > 0 && Character.isLowSurrogate(old[old.length - suffix])) suffix--
    return TextEdit(prefix, old.length - suffix, new.substring(prefix, new.length - suffix))
  }

  private fun requireFits(hunk: Hunk, old: List<String>, now: List<String>) {
    require(hunk.beforeEnd <= old.size && hunk.afterEnd <= now.size) { "$hunk does not fit ${old.size} and ${now.size} lines" }
  }
}
