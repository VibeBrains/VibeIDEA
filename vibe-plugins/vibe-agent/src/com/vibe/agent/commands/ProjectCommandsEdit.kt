// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.commands

/**
 * Edits of `.vibe/commands.json` made from the menu: pin, unpin, remove, and where a command stands in the text
 *
 * The file is written by a person and carries their comments and layout, so it is edited as TEXT:
 * parsing it and writing it back would drop every comment and reflow every line of a file that lives in git.
 * A small scanner finds the command's object and the edit touches only the characters it has to.
 * It reads what [com.vibe.agent.util.VibeJsonc] reads: strings and `//` comments.
 *
 * Pure: text in, text out; null when the command is not in the text.
 */
object ProjectCommandsEdit {
  /** Where the command's object starts — the place to put the caret for the Edit entry */
  fun offsetOf(text: String, id: String): Int? = find(text, id)?.start

  /** Sets `pinned`; the field is rewritten in place or added after `id` */
  fun setPinned(text: String, id: String, pinned: Boolean): String? {
    val entry = find(text, id) ?: return null
    val value = pinned.toString()
    entry.pinned?.let { return text.replaceRange(it, value) }
    return text.substring(0, entry.idEnd) + ", \"pinned\": $value" + text.substring(entry.idEnd)
  }

  /** Removes the command together with the comma that separated it from its neighbour and its own line */
  fun remove(text: String, id: String): String? {
    val entry = find(text, id) ?: return null
    var from = entry.start
    var to = entry.end + 1
    val after = skipBlank(text, to)
    if (after < text.length && text[after] == ',') {
      to = after + 1
    }
    else {
      val before = skipBlankBack(text, from)
      if (before >= 0 && text[before] == ',') from = before
    }
    // The line the object stood on goes with it when nothing else is left there
    val lineStart = text.lastIndexOf('\n', from - 1)
    if (text.substring(lineStart + 1, from).isBlank()) {
      val lineEnd = text.indexOf('\n', to).let { if (it < 0) text.length else it }
      if (text.substring(to, lineEnd).isBlank()) {
        from = lineStart + 1
        to = minOf(lineEnd + 1, text.length)
      }
    }
    return text.removeRange(from, to)
  }

  private class Entry(val start: Int) {
    var end = -1
    var id: String? = null
    var idEnd = -1
    var pinned: IntRange? = null
  }

  private fun skipBlank(text: String, from: Int): Int {
    var i = from
    while (i < text.length && text[i].isWhitespace()) i++
    return i
  }

  private fun skipBlankBack(text: String, before: Int): Int {
    var i = before - 1
    while (i >= 0 && text[i].isWhitespace()) i--
    return i
  }

  /**
   * The object with this `id` among the elements of an array
   * Objects nested deeper (`env`) are scanned too and never match: a command is an element of the list, not a value of a field
   */
  private fun find(text: String, id: String): Entry? {
    val found = ArrayList<Entry>()
    // One frame per open bracket: an Entry for an object that is an array element, null for everything else
    val stack = ArrayList<Entry?>()
    val arrays = ArrayList<Boolean>()
    var i = 0
    while (i < text.length) {
      val c = text[i]
      when {
        c == '/' && text.startsWith("//", i) -> { i = text.indexOf('\n', i).let { if (it < 0) text.length else it }; continue }
        c == '[' -> { stack.add(null); arrays.add(true) }
        c == '{' -> { stack.add(if (arrays.lastOrNull() == true) Entry(i) else null); arrays.add(false) }
        c == ']' || c == '}' -> {
          arrays.removeLastOrNull()
          stack.removeLastOrNull()?.let { it.end = i; found.add(it) }
        }
        c == '"' -> {
          val end = stringEnd(text, i)
          val entry = stack.lastOrNull()
          val colon = skipBlank(text, end + 1)
          if (entry != null && colon < text.length && text[colon] == ':') {
            val value = skipBlank(text, colon + 1)
            when (text.substring(i + 1, end)) {
              "id" -> if (value < text.length && text[value] == '"') {
                val valueEnd = stringEnd(text, value)
                entry.id = text.substring(value + 1, valueEnd)
                entry.idEnd = valueEnd + 1
              }
              "pinned" -> LITERALS.firstOrNull { text.startsWith(it, value) }?.let { entry.pinned = value until value + it.length }
            }
            i = colon + 1
            continue
          }
          i = end + 1
          continue
        }
      }
      i++
    }
    return found.firstOrNull { it.id == id && it.end > it.start }
  }

  /** Index of the quote that closes the string opened at [open] */
  private fun stringEnd(text: String, open: Int): Int {
    var i = open + 1
    while (i < text.length) {
      when (text[i]) {
        '\\' -> i++
        '"' -> return i
      }
      i++
    }
    return text.length - 1
  }

  private val LITERALS = listOf("true", "false")
}
