// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.util

/**
 * Values of the small YAML header that rule and skill files carry.
 *
 * The header is written by people and by two products, and YAML's canonical way to keep a colon
 * inside a value is a quoted scalar or a block scalar.
 * A hand-rolled reader that leaves the quotes and the `>` in the text shows them in the rule list
 * and in the skill popup, which is how a correct header becomes a wrong description.
 */
object YamlHeader {
  /** The scalar and the index of the first line after it; a block scalar consumes the lines below it. */
  data class Value(val text: String, val next: Int)

  /**
   * Reads the value of a `key:` line.
   * [raw] is the text after the colon, [lines] is the whole header and [from] is the first line after the key line.
   */
  fun read(raw: String, lines: List<String>, from: Int): Value {
    val text = raw.trim()
    if (!isBlock(text)) return Value(scalar(text), from)
    val body = ArrayList<String>()
    var i = from
    while (i < lines.size) {
      val line = lines[i]
      if (line.isBlank()) {
        body.add("")
        i++
        continue
      }
      if (!line.first().isWhitespace()) break
      body.add(line)
      i++
    }
    return Value(blockText(text, body), i)
  }

  /** True for `>` and `|` with the optional chomping and indentation indicators. */
  fun isBlock(raw: String): Boolean = BLOCK.matches(raw.trim())

  /**
   * A key directly under a top-level [parent] mapping.
   *
   * Deeper keys are skipped on purpose: `metadata.depends` may carry a `vibeVersion` of its own,
   * and that one describes another skill rather than this file
   */
  fun nestedValue(lines: List<String>, parent: String, key: String): String? {
    var i = 0
    while (i < lines.size) {
      val line = lines[i]
      if (line.isBlank() || line.first().isWhitespace() || line.trimStart().startsWith("#")) {
        i++
        continue
      }
      val colon = line.indexOf(':')
      if (colon <= 0) {
        i++
        continue
      }
      val name = line.substring(0, colon).trim()
      val parsed = read(line.substring(colon + 1), lines, i + 1)
      if (name != parent) {
        i = parsed.next
        continue
      }
      var childIndent: Int? = null
      var j = i + 1
      while (j < lines.size) {
        val child = lines[j]
        if (child.isBlank()) {
          j++
          continue
        }
        val indent = child.takeWhile { it.isWhitespace() }.length
        if (indent == 0) break
        if (child.trimStart().startsWith("#")) {
          j++
          continue
        }
        if (childIndent == null) childIndent = indent
        val childColon = child.indexOf(':')
        if (indent != childIndent || childColon <= 0) {
          j++
          continue
        }
        val childName = child.substring(0, childColon).trim()
        val childValue = read(child.substring(childColon + 1), lines, j + 1)
        j = childValue.next
        if (childName == key) return childValue.text.takeIf { it.isNotEmpty() }
      }
      return null
    }
    return null
  }

  /**
   * A single-line scalar: double quotes are stripped and their escapes expanded, single quotes are
   * stripped and `''` becomes `'`; anything else is the value as written.
   */
  fun scalar(raw: String): String {
    val text = raw.trim()
    if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) return doubleQuoted(text.substring(1, text.length - 1))
    if (text.length >= 2 && text.startsWith('\'') && text.endsWith('\'')) return text.substring(1, text.length - 1).replace("''", "'")
    return text
  }

  /** Expands the escapes YAML defines for double-quoted scalars; an unknown escape stays as written. */
  private fun doubleQuoted(text: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < text.length) {
      val c = text[i++]
      if (c != '\\' || i >= text.length) {
        out.append(c)
        continue
      }
      when (val escaped = text[i++]) {
        '"' -> out.append('"')
        '\\' -> out.append('\\')
        '/' -> out.append('/')
        'n' -> out.append('\n')
        't' -> out.append('\t')
        'r' -> out.append('\r')
        'b' -> out.append('\b')
        'f' -> out.append('\u000C')
        '0' -> out.append('\u0000')
        'u' -> {
          val hex = text.substring(i, minOf(i + 4, text.length))
          val code = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
          if (code == null) {
            out.append("\\u").append(hex)
          }
          else {
            out.append(code.toChar())
          }
          i += hex.length
        }
        else -> out.append('\\').append(escaped)
      }
    }
    return out.toString()
  }

  /** A literal `|` keeps the lines, a folded `>` joins them with spaces; a blank line becomes a newline. */
  private fun blockText(indicator: String, lines: List<String>): String {
    val digits = indicator.drop(1).filter { it.isDigit() }
    val firstIndent = lines.firstOrNull { it.isNotBlank() }?.takeWhile { it.isWhitespace() }?.length ?: 0
    val indent = digits.firstOrNull()?.digitToInt() ?: firstIndent
    val stripped = lines.map { if (it.length >= indent) it.substring(indent) else "" }
    val text = if (indicator.startsWith('|')) stripped.joinToString("\n") else folded(stripped)
    return text.trimEnd()
  }

  /** YAML folding: one newline between plain lines becomes a space, a blank line keeps a newline. */
  private fun folded(lines: List<String>): String {
    val out = StringBuilder()
    var breaks = 0
    for (line in lines) {
      if (line.isBlank()) {
        breaks++
        continue
      }
      if (out.isNotEmpty()) {
        if (breaks == 0) out.append(' ') else repeat(breaks) { out.append('\n') }
      }
      out.append(line)
      breaks = 0
    }
    return out.toString()
  }

  /** `>` or `|`, then an optional chomping sign and an optional indentation digit in either order. */
  private val BLOCK = Regex("^[>|](?:[+-]?[1-9]?|[1-9][+-]?)$")
}
