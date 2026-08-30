// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.rag

/**
 * Splitting files into pieces small enough to be searched by meaning.
 *
 * The unit of an answer is not a file: a 2000-line file has one embedding, and that embedding means
 * «этот файл вообще про всё». It is also not a line: a line has no context and matches everything
 * shaped like it. What works is a window of a few dozen lines with an overlap, so a match near a
 * boundary is not cut in half — the overlap is the whole reason the boundary stops mattering.
 */
object Chunker {
  const val LINES_PER_CHUNK = 60
  const val OVERLAP_LINES = 10

  /** Beyond this a file is generated, minified or data: embedding it buys noise. */
  const val MAX_FILE_CHARS = 400_000

  /** A chunk with the lines it came from, so a hit can be opened at the right place. */
  data class Chunk(val path: String, val fromLine: Int, val toLine: Int, val text: String) {
    val id: String get() = path + "#" + fromLine + "-" + toLine
  }

  fun chunk(path: String, text: String, linesPerChunk: Int = LINES_PER_CHUNK, overlap: Int = OVERLAP_LINES): List<Chunk> {
    if (text.isBlank() || text.length > MAX_FILE_CHARS) return emptyList()
    val lines = text.lines()
    if (lines.isEmpty()) return emptyList()
    val step = (linesPerChunk - overlap).coerceAtLeast(1)
    val chunks = ArrayList<Chunk>()
    var start = 0
    while (start < lines.size) {
      val end = minOf(start + linesPerChunk, lines.size)
      val body = lines.subList(start, end).joinToString("\n")
      // A window of nothing but blank lines and braces is not worth an embedding call.
      if (body.count { it.isLetterOrDigit() } >= MIN_MEANINGFUL_CHARS) {
        chunks.add(Chunk(path, start + 1, end, body))
      }
      if (end == lines.size) break
      start += step
    }
    return chunks
  }

  /** Files worth indexing at all: source and prose, never binaries or vendored trees. */
  fun isIndexable(path: String): Boolean {
    val lower = path.lowercase()
    if (SKIP_DIRECTORIES.any { lower.contains("/" + it + "/") || lower.startsWith(it + "/") }) return false
    val extension = lower.substringAfterLast('.', "")
    return extension in INDEXABLE_EXTENSIONS
  }

  private const val MIN_MEANINGFUL_CHARS = 20

  private val SKIP_DIRECTORIES = listOf(
    "node_modules", "dist", "build", "out", "target", "vendor", ".git", ".idea", "__pycache__",
  )

  private val INDEXABLE_EXTENSIONS = setOf(
    "kt", "kts", "java", "ts", "tsx", "js", "jsx", "mjs", "cjs", "php", "py", "go", "rs", "rb", "cs",
    "c", "h", "cpp", "hpp", "swift", "sql", "sh", "md", "mdx", "json", "yaml", "yml", "toml", "gradle",
  )
}
