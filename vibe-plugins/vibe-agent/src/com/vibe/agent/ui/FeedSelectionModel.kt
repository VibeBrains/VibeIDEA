// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

/**
 * Which part of each piece of the feed is selected, when the selection runs across pieces.
 *
 * The chat feed is a stack of independent components — prose, rendered markdown, code blocks,
 * service lines — and Swing selects inside ONE component. That is the whole of the owner's
 * complaint (18.09.2026): a triple click took a wrapped visual row rather than the message, and
 * Ctrl+A stopped at the first code block, because it asked the focused component and no one else.
 *
 * So the selection is modelled here, once, and the components are told what part of them is
 * selected. Pure: takes lengths and two anchors, returns ranges — an ordinary arithmetic bug in a
 * selection is otherwise reproduced only by hand, with a mouse.
 */
object FeedSelectionModel {
  /** A place in the feed: which piece, and how far into its text. */
  data class Anchor(val index: Int, val offset: Int)

  /** The selected part of one piece; `start == end` means nothing of it is selected. */
  data class Range(val index: Int, val start: Int, val end: Int)

  /**
   * Ranges from [anchor] to [focus], in feed order.
   *
   * The drag may go up as readily as down, so the pair is normalized rather than trusted. Offsets
   * are clamped into their own piece: a point in the gap between two bubbles arrives as «the end of
   * the piece above», and that must not select past its text.
   */
  fun ranges(anchor: Anchor, focus: Anchor, lengths: List<Int>): List<Range> {
    if (lengths.isEmpty()) return emptyList()
    val from = clamp(anchor, lengths)
    val to = clamp(focus, lengths)
    val forwards = from.index < to.index || (from.index == to.index && from.offset <= to.offset)
    val first = if (forwards) from else to
    val last = if (forwards) to else from
    if (first.index == last.index) {
      return listOf(Range(first.index, first.offset, last.offset))
    }
    return (first.index..last.index).map { index ->
      when (index) {
        first.index -> Range(index, first.offset, lengths[index])
        last.index -> Range(index, 0, last.offset)
        else -> Range(index, 0, lengths[index])
      }
    }
  }

  /** Everything, as Ctrl+A means it: every piece of the feed from its start to its end. */
  fun all(lengths: List<Int>): List<Range> = lengths.mapIndexed { index, length -> Range(index, 0, length) }

  /** The two ends of a whole-feed selection, for a drag that continues from Ctrl+A. */
  fun wholeFeed(lengths: List<Int>): Pair<Anchor, Anchor>? =
    if (lengths.isEmpty()) null else Anchor(0, 0) to Anchor(lengths.lastIndex, lengths.last())

  private fun clamp(anchor: Anchor, lengths: List<Int>): Anchor {
    val index = anchor.index.coerceIn(0, lengths.lastIndex)
    return Anchor(index, anchor.offset.coerceIn(0, lengths[index]))
  }
}
