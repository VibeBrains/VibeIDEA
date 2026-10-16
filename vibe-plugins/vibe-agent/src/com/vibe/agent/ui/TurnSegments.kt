// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import com.vibe.agent.history.ChatMessageRecord
import com.vibe.agent.history.Role

/**
 * The order of one turn's words and calls: text said between two tool calls is a segment of its own
 *
 * An agent streams "text, tool calls, text, tool calls, report", and the feed has to show exactly that
 * One buffer for the whole turn glued every sentence into one bubble and put all the calls after it,
 * so the commands looked as if they ran after the report
 *
 * Two threads touch a segment: the reader appends and cuts, the EDT projects the segment into a row
 * A projection reads its own [Segment], not "the current buffer": a chunk of the next segment may already be there
 */
internal class TurnSegments {
  /** One run of words between two boundaries; [message] and [consumed] belong to the EDT */
  class Segment {
    val text = StringBuffer()
    @Volatile var consumed = 0
    @Volatile var retired = false
    var message: AgentPanel.AgentMessage? = null
  }

  @Volatile var current = Segment()
    private set

  /** How many segments the turn has already closed and stored; the turn's last words are not among them */
  @Volatile var cutCount = 0
    private set

  /** Adds a chunk to the open segment and returns the segment it went to, for the projection to read */
  fun append(chunk: String): Segment = synchronized(this) { current.also { it.text.append(chunk) } }

  /**
   * Closes the open segment at a boundary (a tool call, a thought) and opens the next
   * Returns the closed segment, or null when nothing was said since the last boundary
   * Whitespace alone is not a segment: it would be an empty bubble in the feed and an empty record in the thread
   */
  fun cut(): Segment? = synchronized(this) {
    val closed = current
    if (closed.text.isEmpty()) return null
    current = Segment()
    if (closed.text.isNotBlank()) cutCount++
    closed
  }

  /** The record of a closed segment; null for whitespace */
  fun record(segment: Segment, at: String): ChatMessageRecord? =
    segment.text.toString().takeIf { it.isNotBlank() }?.let { ChatMessageRecord(Role.ASSISTANT, it, at = at, interim = true) }

  /** The turn is over: the next one counts from zero */
  fun reset() = synchronized(this) { cutCount = 0 }
}
