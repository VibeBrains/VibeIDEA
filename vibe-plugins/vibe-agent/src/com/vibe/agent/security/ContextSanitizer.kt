// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.security

import com.vibe.agent.i18n.VibeI18n.t

/**
 * Guards what goes INTO the model's context.
 *
 * The registry, the audit and the turn checks all watch what the agent *writes*. Nothing watched
 * what it *reads* — and a file is the cheapest way to talk to someone else's agent: it is enough to
 * put a sentence into a README of a library, and every agent that ever reads it hears an
 * instruction. Three classes of problem, three different policies, because the right answer is not
 * the same for all of them:
 *
 * - **Invisible characters** — removed silently. Zero-width spaces and joiners, the soft hyphen and
 *   especially the Unicode tag block (U+E0000–E007F), which renders as *nothing at all* and is the
 *   standard way to hide a prompt inside a line a human reviews and approves. Text that a person
 *   cannot see must never reach a model, and no legitimate source file needs it.
 * - **Bidi overrides** — removed. The Trojan Source trick: the file reads one way to a human and
 *   another to a compiler or a model. Source code has no honest use for them.
 * - **Instruction-shaped phrases** — REPORTED, never removed. Documentation, tests and this very
 *   file legitimately contain «ignore previous instructions»; silently rewriting a user's file
 *   content is worse than telling them what was found and letting them look.
 * - **Secrets** — reported, and optionally masked in what we send (never in the file itself).
 *
 * Pure: no IO, no IDE, no settings lookups — the caller decides what to do with the findings.
 */
object ContextSanitizer {
  enum class Kind { INVISIBLE, BIDI, INSTRUCTION, SECRET }

  data class Finding(val kind: Kind, val detail: String, val count: Int = 1)

  data class Result(val text: String, val findings: List<Finding>) {
    val isClean: Boolean get() = findings.isEmpty()
  }

  /**
   * @param maskSecrets replace credential-shaped substrings with a marker in the returned text.
   *                    The file on disk is never touched — this only affects what we transmit.
   */
  fun sanitize(text: String, maskSecrets: Boolean = false): Result {
    if (text.isEmpty()) return Result(text, emptyList())
    val findings = ArrayList<Finding>()

    var invisible = 0
    var bidi = 0
    val cleaned = buildString(text.length) {
      var index = 0
      while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val width = Character.charCount(codePoint)
        when {
          isInvisible(codePoint) -> invisible++
          isBidiControl(codePoint) -> bidi++
          else -> appendCodePoint(codePoint)
        }
        index += width
      }
    }
    if (invisible > 0) findings.add(Finding(Kind.INVISIBLE, t("sanitizer.invisible"), invisible))
    if (bidi > 0) findings.add(Finding(Kind.BIDI, t("sanitizer.bidi"), bidi))

    val phrase = SecurityPhrases.INSTRUCTIONS.firstOrNull { it.containsMatchIn(cleaned) }
    if (phrase != null) findings.add(Finding(Kind.INSTRUCTION, t("sanitizer.instruction")))

    val secrets = SecretPatterns.labels(cleaned)
    secrets.forEach { findings.add(Finding(Kind.SECRET, it)) }

    val result = if (maskSecrets && secrets.isNotEmpty()) SecretPatterns.redact(cleaned) else cleaned
    return Result(result, findings)
  }

  /** Note prepended once per message so the model is told, in words, that context is data. */
  val DATA_NOT_INSTRUCTIONS: String get() = t("sanitizer.dataNotInstructions")

  private fun isInvisible(codePoint: Int): Boolean = when (codePoint) {
    0x00AD -> true                       // soft hyphen
    0x200B, 0x200C, 0x200D -> true       // zero-width space / non-joiner / joiner
    0xFEFF -> true                       // zero-width no-break space (BOM in the middle of text)
    in 0x2060..0x2064 -> true            // word joiner, invisible operators
    in 0xE0000..0xE007F -> true          // tag characters: render as nothing, carry hidden text
    else -> false
  }

  // U+200E/200F (LRM/RLM) are marks, not overrides, and appear in honest bilingual text; the
  // embedding/override/isolate controls below are the ones that reorder a line.
  private fun isBidiControl(codePoint: Int): Boolean = when (codePoint) {
    in 0x202A..0x202E -> true            // LRE, RLE, PDF, LRO, RLO
    in 0x2066..0x2069 -> true            // LRI, RLI, FSI, PDI
    else -> false
  }

}
