// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.minimalism

/**
 * `/simplify`: the current diff read back as a DELETE LIST.
 *
 * Review asks «правильно ли это». This asks a different question — «что из этого можно убрать, ничего
 * не потеряв» — and it is the question nobody asks, because the code was just written and every line
 * of it felt necessary an hour ago.
 *
 * The answer must be actionable, so its shape is fixed: file, line, what to remove, why. A model
 * answering in prose has answered a different question, and the parser says so rather than showing
 * an essay.
 */
object SimplifyPrompt {
  data class Item(val file: String, val line: Int?, val what: String, val why: String)

  /** Files and the lines the diff ADDED — removals need no simplification. */
  data class DiffFile(val path: String, val addedLines: List<Pair<Int, String>>)

  private val FILE_HEADER = Regex("^\\+\\+\\+ b/(.+)$")
  private val HUNK = Regex("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@")

  /** Parses a unified diff. Only additions are kept: the delete list is about what was just written. */
  fun parseDiff(diff: String): List<DiffFile> {
    val files = ArrayList<DiffFile>()
    var path: String? = null
    var added = ArrayList<Pair<Int, String>>()
    var lineNumber = 0
    for (raw in diff.lines()) {
      val fileHeader = FILE_HEADER.find(raw)
      val hunk = HUNK.find(raw)
      when {
        fileHeader != null -> {
          path?.let { files.add(DiffFile(it, added)) }
          path = fileHeader.groupValues[1]
          added = ArrayList()
        }
        // The hunk header is not a line of the file: counting it shifted every number by one,
        // and a delete list pointing one line off is a delete list nobody trusts twice.
        hunk != null -> lineNumber = hunk.groupValues[1].toIntOrNull() ?: 1
        raw.startsWith("---") -> {}
        raw.startsWith("+") -> {
          added.add(lineNumber to raw.drop(1))
          lineNumber++
        }
        raw.startsWith("-") -> {}
        else -> lineNumber++
      }
    }
    path?.let { files.add(DiffFile(it, added)) }
    return files.filter { it.addedLines.isNotEmpty() }
  }

  /** The prompt: the diff itself plus the ladder of what counts as removable. */
  fun build(diff: String, instruction: String, ladder: String): String = buildString {
    appendLine(instruction)
    appendLine()
    appendLine(ladder)
    appendLine()
    appendLine("```diff")
    appendLine(diff.take(MAX_DIFF_CHARS))
    append("```")
  }

  /**
   * Reads the answer back as a list. The expected shape is `path:line — что убрать — почему`, and
   * anything that does not parse is reported as unparsed rather than dropped: a delete list that
   * silently loses half its items is worse than none.
   */
  fun parseAnswer(answer: String): Pair<List<Item>, List<String>> {
    val items = ArrayList<Item>()
    val unparsed = ArrayList<String>()
    for (raw in answer.lines()) {
      val line = raw.trim().removePrefix("-").removePrefix("*").trim()
      if (line.isEmpty() || line.startsWith("#") || line.startsWith("```")) continue
      val parts = line.split(" — ", " - ", limit = 3)
      if (parts.size < 2) {
        if (line.length > MIN_UNPARSED_LENGTH) unparsed.add(line)
        continue
      }
      val location = parts[0].trim()
      val file = location.substringBeforeLast(':').trim()
      val number = location.substringAfterLast(':', "").trim().toIntOrNull()
      if (file.isEmpty()) {
        unparsed.add(line)
        continue
      }
      items.add(Item(file, number, parts[1].trim(), parts.getOrNull(2)?.trim().orEmpty()))
    }
    return items to unparsed
  }

  const val MAX_DIFF_CHARS = 60_000
  private const val MIN_UNPARSED_LENGTH = 12
}
