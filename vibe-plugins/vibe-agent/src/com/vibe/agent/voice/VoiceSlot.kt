// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

/**
 * Where a dictation writes in the input, and the check that it still writes where it wrote before
 *
 * The draft of a phrase goes into the input itself and is replaced by the next draft and then by the phrase
 * The input is not ours alone: the person may click, type or paste in the middle of a dictation
 * So every change first checks that the text it put there is still at the place it put it; when it moved, the text is
 * looked for, and when it is gone, writing starts again at the caret
 * Replacing at a remembered offset without that check turns each draft into one more copy of itself, as it did in VibeIDE
 * Pure: the text of the input in, the edit out; the composer applies it
 */
class VoiceSlot {
  /** Replace [from] until [to] of the input with [text], and put the caret after it */
  data class Edit(val from: Int, val to: Int, val text: String)

  private var start = -1
  private var shown = ""
  private var prefix = ""
  private val phrases = ArrayList<String>()
  private var draft = ""

  /** Whether this dictation has put anything in the input */
  val active: Boolean get() = start >= 0

  /** The draft of the phrase being spoken; null when the input already shows it */
  fun draft(text: String, input: String, caret: Int): Edit? = update(input, caret) { draft = text.trim() }

  /** A phrase is final: it replaces its draft and stays */
  fun phrase(text: String, input: String, caret: Int): Edit? = update(input, caret) {
    draft = ""
    text.trim().takeIf { it.isNotEmpty() }?.let { phrases += it }
  }

  /**
   * The dictation is over
   * With [keep] what it wrote stays, without the draft of an unfinished phrase; without it everything it wrote is taken out
   */
  fun close(keep: Boolean, input: String): Edit? {
    val at = locate(input)
    val edit = when {
      at < 0 -> null
      keep -> Edit(at, at + shown.length, textOf(withDraft = false)).takeIf { it.text != shown }
      else -> Edit(at, at + shown.length, "")
    }
    start = -1
    shown = ""
    prefix = ""
    phrases.clear()
    draft = ""
    return edit
  }

  /**
   * The input without the draft of the phrase being spoken: what may be sent or kept as the draft of the chat
   * A word half heard is never sent, and never stays in a chat the person switched away from
   */
  fun withoutDraft(input: String): String {
    val at = locate(input)
    if (at < 0 || draft.isEmpty()) return input
    return input.substring(0, at) + textOf(withDraft = false) + input.substring(at + shown.length)
  }

  /** Finds what was written, or starts afresh at the caret, then applies [change] and says what to replace */
  private fun update(input: String, caret: Int, change: () -> Unit): Edit? {
    var at = locate(input)
    if (at < 0) {
      // Nothing of ours is where we left it, or anywhere: what was said before is the person's now, and writing starts afresh
      phrases.clear()
      at = caret.coerceIn(0, input.length)
      start = at
      shown = ""
      prefix = if (at > 0 && !input[at - 1].isWhitespace()) SEPARATOR else ""
    }
    change()
    val text = textOf(withDraft = true)
    if (text == shown) return null
    val edit = Edit(at, at + shown.length, text)
    shown = text
    return edit
  }

  /** The offset of what this dictation wrote, or -1; with nothing written yet there is nothing to find, and the caret decides */
  private fun locate(input: String): Int {
    if (start < 0 || shown.isEmpty()) return -1
    if (input.startsWith(shown, start)) return start
    val moved = input.lastIndexOf(shown)
    if (moved >= 0) start = moved
    return moved
  }

  private fun textOf(withDraft: Boolean): String {
    val words = if (withDraft && draft.isNotEmpty()) phrases + draft else phrases
    return if (words.isEmpty()) "" else prefix + words.joinToString(SEPARATOR)
  }

  private companion object {
    const val SEPARATOR = " "
  }
}
