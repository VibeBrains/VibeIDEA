// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Edits of a servers file made from the settings page: switch a server off and on, remove it, add one
 * Either file takes them, the project's `.vibe/mcp.json` or the shared `~/.vibe/mcp.json`: the text is the same
 *
 * The file is shared with VibeIDE and kept in git, so it is edited as TEXT, not parsed and written back:
 * a rewrite would reorder keys, reflow the layout and drop what this product does not know (`tools`,
 * `url` entries of the other one). A small scanner finds the member to touch and the edit changes only the characters
 * it has to, in the style the file already has (indent, line breaks, one line or many).
 *
 * Pure: text in, text out. An edit that would leave the file unreadable is refused, never written.
 */
object McpServersEdit {
  sealed interface Outcome {
    data class Edited(val text: String) : Outcome
    data class Refused(val reason: Reason) : Outcome
  }

  enum class Reason {
    /** The text is not a JSON object, or `mcpServers` in it is not an object: there is nothing safe to edit */
    UNREADABLE,

    /** The file has no server under this name */
    NO_SUCH_SERVER,

    /** The file already has a server under this name */
    NAME_TAKEN,
  }

  /** Where the entry of a server starts — the place to put the caret for the Edit link */
  fun offsetOf(text: String, name: String): Int? {
    val section = sectionOf(text) ?: return null
    return section.members.lastOrNull { it.key == name }?.keyStart
  }

  /** `"disabled": true` for a server that is off; the field is removed for one that is on, so the entry looks as before */
  fun setDisabled(text: String, name: String, disabled: Boolean): Outcome {
    val section = sectionOf(text) ?: return Outcome.Refused(Reason.UNREADABLE)
    val member = section.members.lastOrNull { it.key == name } ?: return Outcome.Refused(Reason.NO_SUCH_SERVER)
    val entry = objectAt(text, member.valueStart) ?: return Outcome.Refused(Reason.UNREADABLE)
    val field = entry.members.lastOrNull { it.key == DISABLED }
    val edited = when {
      disabled && field != null -> text.replaceRange(field.valueStart, field.valueEnd, TRUE)
      disabled -> appendMember(text, entry) { _, _ -> "${quote(DISABLED)}: $TRUE" }
      field != null -> removeMember(text, entry, entry.members.lastIndexOf(field))
      else -> text
    }
    return checked(edited)
  }

  /** The entry is cut out together with the comma that separated it from its neighbour */
  fun remove(text: String, name: String): Outcome {
    val section = sectionOf(text) ?: return Outcome.Refused(Reason.UNREADABLE)
    val index = section.members.indexOfLast { it.key == name }
    if (index < 0) return Outcome.Refused(Reason.NO_SUCH_SERVER)
    return checked(removeMember(text, section, index))
  }

  /**
   * The entry goes last, laid out like its neighbours; [text] null or blank is a file that does not exist yet
   *
   * A file without the `mcpServers` section gets the section first: other top-level keys stay where they are
   */
  fun add(text: String?, entry: McpServersFile.Entry): Outcome {
    var current = text?.takeIf { it.isNotBlank() } ?: NEW_FILE
    val root = rootOf(current) ?: return Outcome.Refused(Reason.UNREADABLE)
    val existing = root.members.lastOrNull { it.key == SECTION }
    when {
      existing == null -> current = appendMember(current, root) { _, _ -> "${quote(SECTION)}: {}" }
      current.getOrNull(existing.valueStart) != '{' -> return Outcome.Refused(Reason.UNREADABLE)
    }
    val section = sectionOf(current) ?: return Outcome.Refused(Reason.UNREADABLE)
    if (section.members.any { it.key == entry.name }) return Outcome.Refused(Reason.NAME_TAKEN)
    val style = styleOf(current)
    val edited = appendMember(current, section) { indent, multiline -> member(entry, indent, multiline, style) }
    return checked(edited)
  }

  private const val SECTION = "mcpServers"
  private const val DISABLED = "disabled"
  private const val TRUE = "true"
  private const val NEW_FILE = "{\n  \"$SECTION\": {}\n}\n"
  private const val DEFAULT_UNIT = "  "

  private class Member(val key: String, val keyStart: Int, val valueStart: Int, val valueEnd: Int)

  private class Obj(val open: Int, val close: Int, val members: List<Member>)

  private class Style(val newline: String, val unit: String, val multiline: Boolean)

  private fun isObject(text: String): Boolean = runCatching { Json.parseToJsonElement(text) is JsonObject }.getOrDefault(false)

  /** The edit goes out only if what it made still reads as JSON: a scanner slip must refuse, not corrupt the file */
  private fun checked(edited: String): Outcome =
    if (isObject(edited)) Outcome.Edited(edited) else Outcome.Refused(Reason.UNREADABLE)

  private fun rootOf(text: String): Obj? {
    if (!isObject(text)) return null
    val open = skipSpace(text, 0)
    return if (text.getOrNull(open) == '{') objectAt(text, open) else null
  }

  /** The `mcpServers` object; null when the text is unreadable or has no such object */
  private fun sectionOf(text: String): Obj? {
    val root = rootOf(text) ?: return null
    val member = root.members.lastOrNull { it.key == SECTION } ?: return null
    return if (text.getOrNull(member.valueStart) == '{') objectAt(text, member.valueStart) else null
  }

  // ----- scanning: strict JSON, spans only -----

  private fun skipSpace(text: String, from: Int): Int {
    var i = from
    while (i < text.length && text[i].isWhitespace()) i++
    return i
  }

  /** Index of the quote that closes the string opened at [open]; -1 when it never closes */
  private fun stringEnd(text: String, open: Int): Int {
    var i = open + 1
    while (i < text.length) {
      when (text[i]) {
        '\\' -> i++
        '"' -> return i
      }
      i++
    }
    return -1
  }

  private fun decode(literal: String): String =
    runCatching { (Json.parseToJsonElement(literal) as JsonPrimitive).content }.getOrDefault(literal.trim('"'))

  private fun objectAt(text: String, open: Int): Obj? {
    if (text.getOrNull(open) != '{') return null
    val members = ArrayList<Member>()
    var i = skipSpace(text, open + 1)
    if (text.getOrNull(i) == '}') return Obj(open, i, members)
    while (i < text.length) {
      if (text[i] != '"') return null
      val keyEnd = stringEnd(text, i)
      if (keyEnd < 0) return null
      val colon = skipSpace(text, keyEnd + 1)
      if (text.getOrNull(colon) != ':') return null
      val valueStart = skipSpace(text, colon + 1)
      val valueEnd = valueEnd(text, valueStart) ?: return null
      members += Member(decode(text.substring(i, keyEnd + 1)), i, valueStart, valueEnd)
      val after = skipSpace(text, valueEnd)
      when (text.getOrNull(after)) {
        ',' -> i = skipSpace(text, after + 1)
        '}' -> return Obj(open, after, members)
        else -> return null
      }
    }
    return null
  }

  private fun arrayEnd(text: String, open: Int): Int? {
    var i = skipSpace(text, open + 1)
    if (text.getOrNull(i) == ']') return i + 1
    while (i < text.length) {
      i = skipSpace(text, valueEnd(text, i) ?: return null)
      when (text.getOrNull(i)) {
        ',' -> i = skipSpace(text, i + 1)
        ']' -> return i + 1
        else -> return null
      }
    }
    return null
  }

  /** Index just past the value that starts at [start] */
  private fun valueEnd(text: String, start: Int): Int? = when (text.getOrNull(start)) {
    null -> null
    '"' -> stringEnd(text, start).takeIf { it >= 0 }?.plus(1)
    '{' -> objectAt(text, start)?.let { it.close + 1 }
    '[' -> arrayEnd(text, start)
    else -> {
      var i = start
      while (i < text.length && text[i] !in LITERAL_STOPS) i++
      i.takeIf { it > start }
    }
  }

  private const val LITERAL_STOPS = ",}] \t\r\n"

  // ----- editing -----

  private fun removeMember(text: String, obj: Obj, index: Int): String {
    val member = obj.members[index]
    return when {
      // The last one left: the braces stay and the line it stood on goes with it
      obj.members.size == 1 -> text.removeRange(obj.open + 1, obj.close)
      // Up to the start of the next key: takes the comma and the line break, and the neighbour keeps its own indent
      index < obj.members.lastIndex -> text.removeRange(member.keyStart, obj.members[index + 1].keyStart)
      // The last of several: back to the end of the previous value, which takes the comma before it
      else -> text.removeRange(obj.members[index - 1].valueEnd, member.valueEnd)
    }
  }

  /**
   * Puts one member at the end of an object, in the object's own manner: under the last key on its own line
   * when the object spans lines, after the last value on the same line when it does not
   *
   * [render] gets the indent of the member and whether it lies on lines of its own
   */
  private fun appendMember(text: String, obj: Obj, render: (indent: String, multiline: Boolean) -> String): String {
    val style = styleOf(text)
    val spansLines = text.substring(obj.open, obj.close).contains('\n')
    val last = obj.members.lastOrNull()
    if (last != null) {
      if (!spansLines) return text.replaceRange(last.valueEnd, last.valueEnd, ", " + render("", false))
      val indent = lineIndent(text, last.keyStart)
      return text.replaceRange(last.valueEnd, last.valueEnd, "," + style.newline + indent + render(indent, true))
    }
    if (!style.multiline) return text.replaceRange(obj.open + 1, obj.close, " " + render("", false) + " ")
    val base = lineIndent(text, obj.open)
    val indent = base + style.unit
    return text.replaceRange(obj.open + 1, obj.close, style.newline + indent + render(indent, true) + style.newline + base)
  }

  /** Whitespace the line of [position] starts with */
  private fun lineIndent(text: String, position: Int): String {
    val lineStart = text.lastIndexOf('\n', position - 1) + 1
    return text.substring(lineStart).takeWhile { it == ' ' || it == '\t' }
  }

  private fun styleOf(text: String): Style {
    val unit = text.lineSequence().map { line -> line.takeWhile { it == ' ' || it == '\t' } }.firstOrNull { it.isNotEmpty() }
    return Style(if (text.contains("\r\n")) "\r\n" else "\n", unit ?: DEFAULT_UNIT, text.contains('\n'))
  }

  /** `"name": { ... }`: fields on lines of their own when the neighbours are, args and env on one line each */
  private fun member(entry: McpServersFile.Entry, indent: String, multiline: Boolean, style: Style): String {
    val fields = buildList {
      add("${quote("command")}: ${quote(entry.command)}")
      if (entry.args.isNotEmpty()) add("${quote("args")}: [" + entry.args.joinToString(", ") { quote(it) } + "]")
      if (entry.env.isNotEmpty()) {
        add("${quote("env")}: { " + entry.env.entries.joinToString(", ") { "${quote(it.key)}: ${quote(it.value)}" } + " }")
      }
      entry.cwd?.let { add("${quote("cwd")}: ${quote(it)}") }
      if (entry.disabled) add("${quote(DISABLED)}: $TRUE")
    }
    val head = "${quote(entry.name)}: "
    if (!multiline) return head + "{ " + fields.joinToString(", ") + " }"
    val inner = indent + style.unit
    return head + "{" + style.newline + fields.joinToString("," + style.newline) { inner + it } + style.newline + indent + "}"
  }

  private fun quote(value: String): String = JsonPrimitive(value).toString()
}
