// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.inline

/**
 * Inline editing (Ctrl+K): change THIS code, right here, without opening a chat.
 *
 * The chat is the wrong shape for a small edit. Explaining which function, pasting it, waiting for
 * prose around the answer and then copying the result back costs more attention than the edit is
 * worth — which is why people stop asking and just write it themselves. Here the selection IS the
 * question, and the answer replaces it.
 *
 * Everything below is pure: what the model is asked, and what counts as a usable answer.
 */
object InlineEditPrompt {
  /** The five verbs people actually use; anything else is a free-form instruction. */
  enum class Command { DOC, REFACTOR, TESTS, EXPLAIN, FIX, FREE }

  data class Request(val command: Command, val instruction: String) {
    /** EXPLAIN answers in prose and must never replace the code with its own explanation. */
    val replacesCode: Boolean get() = command != Command.EXPLAIN
  }

  fun parse(input: String): Request {
    val text = input.trim()
    if (!text.startsWith("/")) return Request(Command.FREE, text)
    val word = text.drop(1).substringBefore(' ').lowercase()
    val rest = text.drop(1).substringAfter(' ', "").trim()
    val command = when (word) {
      "doc", "док" -> Command.DOC
      "refactor", "рефактор" -> Command.REFACTOR
      "tests", "test", "тесты" -> Command.TESTS
      "explain", "объясни" -> Command.EXPLAIN
      "fix", "почини" -> Command.FIX
      else -> return Request(Command.FREE, text)
    }
    return Request(command, rest)
  }

  /**
   * The prompt. [rules] carries the project's own rules — an inline edit that ignores them produces
   * code the next review rejects, which is worse than no help at all.
   */
  fun build(request: Request, language: String?, code: String, instructions: Instructions, rules: String? = null): String =
    buildString {
      appendLine(when (request.command) {
        Command.DOC -> instructions.doc
        Command.REFACTOR -> instructions.refactor
        Command.TESTS -> instructions.tests
        Command.EXPLAIN -> instructions.explain
        Command.FIX -> instructions.fix
        Command.FREE -> instructions.free
      })
      if (request.instruction.isNotBlank()) {
        appendLine()
        appendLine(request.instruction)
      }
      rules?.takeIf { it.isNotBlank() }?.let {
        appendLine()
        appendLine(it)
      }
      appendLine()
      if (request.replacesCode) appendLine(instructions.formatRule)
      appendLine()
      appendLine("```" + (language ?: ""))
      appendLine(code)
      append("```")
    }

  data class Instructions(
    val doc: String,
    val refactor: String,
    val tests: String,
    val explain: String,
    val fix: String,
    val free: String,
    val formatRule: String,
  )

  /**
   * Pulls the code out of the answer.
   *
   * Models wrap code in fences and add «вот исправленный вариант» however firmly they are asked not
   * to. Taking the answer literally would paste that sentence into the file, so the fence wins when
   * there is one — and when there is none, an answer that looks like prose is REFUSED rather than
   * pasted: replacing working code with an apology is the one outcome nobody forgives.
   */
  fun extractCode(answer: String, original: String): String? {
    val fenced = FENCE.find(answer)?.groupValues?.get(1)?.trim()
    if (!fenced.isNullOrBlank()) return fenced
    val text = answer.trim()
    if (text.isEmpty()) return null
    if (looksLikeProse(text, original)) return null
    return text
  }

  /**
   * Prose is recognised by shape, not by keywords: keywords differ between languages, and the shape
   * of an explanation — sentences, no punctuation of code — does not.
   */
  fun looksLikeProse(text: String, original: String): Boolean {
    val codeChars = text.count { it in "{};()=<>[]" }
    val originalCodeChars = original.count { it in "{};()=<>[]" }
    // An answer with far less code punctuation than the original is a description of the code.
    if (originalCodeChars >= 4 && codeChars * 4 < originalCodeChars) return true
    val lines = text.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return true
    // A single long sentence ending in a full stop is an answer, not a replacement.
    return lines.size == 1 && lines.first().length > 80 && lines.first().trimEnd().endsWith('.') && codeChars == 0
  }

  private val FENCE = Regex("```[a-zA-Z0-9+#-]*\\s*\\n([\\s\\S]*?)```")
}
