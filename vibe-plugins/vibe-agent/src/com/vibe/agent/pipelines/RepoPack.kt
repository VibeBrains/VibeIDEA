// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

import com.vibe.agent.context.ContextBudget
import com.vibe.agent.security.ContextSanitizer
import com.vibe.agent.security.SecretPatterns

/**
 * `pack` of a pipeline step: which files of the repository go into the prompt of a step on its own model.
 *
 * A step on its own model has no tools and no files — so «analyse the whole repository» on a model with a million-token
 * window was a step that saw nothing but its task. The pack gives it the files as text, and nothing else changes: the
 * step still writes nothing.
 *
 * @param paths gitignore-style patterns of what to include; empty — every tracked file.
 * @param exclude patterns taken out of what [paths] let in.
 * @param maxTokens the pack's ceiling, required: a whole repository without a stated ceiling is a bill without one.
 */
data class PackSpec(val paths: List<String>, val exclude: List<String>, val maxTokens: Int)

/**
 * Builds the pack — pure: the tracked paths and their bytes in, a verdict out. The reading of git and the disk is the
 * caller's.
 *
 * Nothing is cut to fit. A pack over its ceiling is refused whole, because an analysis «of the repository» made from
 * half of it is the one outcome worse than no analysis: it looks complete. Only tracked files are read — `.gitignore`
 * is the project's own statement of what is not source — and a file with a secret in it is left out and named.
 */
object RepoPack {
  /** Bytes looked at to tell text from binary: a NUL among them is a binary file, the way git itself decides. */
  const val BINARY_PROBE_BYTES = 8_000

  sealed interface Result {
    /**
     * @param text the files, each in its own `<file path="…">` block, in path order.
     * @param secrets files left out because a secret pattern matched in them.
     * @param findings files whose text the sanitizer changed, with what it found.
     */
    data class Packed(
      val text: String,
      val files: Int,
      val tokens: Long,
      val secrets: List<String>,
      val binaries: Int,
      val findings: Map<String, List<ContextSanitizer.Finding>>,
    ) : Result

    data class TooLarge(val tokens: Long, val limit: Long, val files: Int) : Result

    /** Nothing matched, or everything that matched was binary or held a secret. */
    data class Empty(val secrets: List<String>) : Result
  }

  /** The tracked paths the spec takes, in path order. */
  fun select(tracked: List<String>, spec: PackSpec): List<String> =
    tracked.asSequence()
      .map { it.replace('\\', '/') }
      .filter { path -> spec.paths.isEmpty() || spec.paths.any { RolePaths.matches(path, it) } }
      .filterNot { path -> spec.exclude.any { RolePaths.matches(path, it) } }
      .distinct().sorted().toList()

  /**
   * @param read the bytes of a selected path; null — it vanished between listing and reading, and is skipped.
   * @param limit the ceiling in tokens — the lesser of the step's `pack.maxTokens` and what the model's window leaves.
   */
  fun build(paths: List<String>, limit: Long, read: (String) -> ByteArray?): Result {
    val out = StringBuilder()
    val secrets = ArrayList<String>()
    val findings = LinkedHashMap<String, List<ContextSanitizer.Finding>>()
    var files = 0
    var binaries = 0
    var tokens = 0L
    for (path in paths) {
      val bytes = read(path) ?: continue
      if (isBinary(bytes)) { binaries++; continue }
      val raw = String(bytes, Charsets.UTF_8)
      if (SecretPatterns.firstMatch(raw) != null) { secrets += path; continue }
      val clean = ContextSanitizer.sanitize(raw)
      if (clean.findings.isNotEmpty()) findings[path] = clean.findings
      val block = block(path, clean.text)
      tokens += ContextBudget.estimateTokens(block)
      files++
      // Counted to the end even past the ceiling: «too large by how much» is what the person needs to narrow `paths`.
      if (tokens <= limit) out.append(block)
    }
    return when {
      files == 0 -> Result.Empty(secrets)
      tokens > limit -> Result.TooLarge(tokens, limit, files)
      else -> Result.Packed(out.toString(), files, tokens, secrets, binaries, findings)
    }
  }

  fun isBinary(bytes: ByteArray): Boolean {
    val n = minOf(bytes.size, BINARY_PROBE_BYTES)
    for (i in 0 until n) if (bytes[i] == 0.toByte()) return true
    return false
  }

  private fun block(path: String, text: String): String =
    "<file path=\"" + path.replace("\"", "&quot;") + "\">\n" + text + (if (text.endsWith("\n")) "" else "\n") + "</file>\n"
}
