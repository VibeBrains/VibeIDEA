// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.fim

import com.intellij.codeInsight.inline.completion.DebouncedInlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.editor.Document
import com.vibe.agent.providers.LlmClient
import com.vibe.agent.providers.ModelEntry
import com.vibe.agent.providers.ProvidersService
import com.vibe.agent.providers.ResolvedProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * FIM autocomplete fed by providers.json models marked `fim: true` (openai protocol).
 * VibeIDE mechanics: 250 ms debounce, 25 lines of prefix/suffix, single-line stop
 * on newline, multi-line stop on blank line; >500-char lines are skipped.
 * Note: VibeIDE itself left FIM for dynamic providers as a follow-up — here it is first-class.
 */
class VibeFimProvider : DebouncedInlineCompletionProvider() {
  override val id: InlineCompletionProviderID = InlineCompletionProviderID("com.vibe.agent.fim")
  private val llm = LlmClient()

  @Volatile private var cached: Pair<ResolvedProvider, ModelEntry>? = null
  @Volatile private var cachedAt: Long = 0

  override suspend fun getDebounceDelay(request: InlineCompletionRequest): Duration = DEBOUNCE_DELAY

  override fun isEnabled(event: InlineCompletionEvent): Boolean =
    event is InlineCompletionEvent.DocumentChange || event is InlineCompletionEvent.DirectCall

  override suspend fun getSuggestionDebounced(request: InlineCompletionRequest): InlineCompletionSuggestion {
    val project = request.editor.project ?: return InlineCompletionSuggestion.Empty
    val target = withContext(Dispatchers.IO) { fimTarget(project.basePath) } ?: return InlineCompletionSuggestion.Empty
    val document = request.document
    val offset = request.endOffset
    val (prefix, suffix, sameLineSuffix) = slice(document, offset)
    if (prefix.lines().lastOrNull().orEmpty().length > MAX_LINE_LENGTH) return InlineCompletionSuggestion.Empty
    val stop = if (sameLineSuffix.isNotBlank()) listOf("\n") else listOf("\n\n")
    val text = withContext(Dispatchers.IO) {
      runCatching { llm.fimComplete(target.first, target.second, prefix, suffix, stop) }.getOrDefault("")
    }.trimEnd('\n')
    if (text.isBlank()) return InlineCompletionSuggestion.Empty
    return InlineCompletionSingleSuggestion.build {
      emit(InlineCompletionGrayTextElement(text))
    }
  }

  private fun slice(document: Document, offset: Int): Triple<String, String, String> {
    val text = document.charsSequence.toString()
    val lineStart = text.lastIndexOf('\n', maxOf(0, offset - 1)).let { if (it < 0) 0 else it + 1 }
    var from = offset
    var lines = 0
    while (from > 0 && lines < CONTEXT_LINES) {
      from = text.lastIndexOf('\n', from - 1).let { if (it < 0) 0 else it }
      lines++
      if (from == 0) break
    }
    var to = offset
    lines = 0
    while (to < text.length && lines < CONTEXT_LINES) {
      val next = text.indexOf('\n', to)
      to = if (next < 0) text.length else next + 1
      lines++
      if (to >= text.length) { to = text.length; break }
    }
    val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
    return Triple(text.subSequence(from, offset).toString(), text.subSequence(offset, to).toString(), text.subSequence(offset, lineEnd).toString())
  }

  private fun fimTarget(projectBase: String?): Pair<ResolvedProvider, ModelEntry>? {
    val now = System.currentTimeMillis()
    if (now - cachedAt < TARGET_CACHE_TTL_MS) return cached
    cachedAt = now
    cached = run {
      for (p in ProvidersService.load(projectBase) { }) {
        val model = p.models.firstOrNull { it.active && it.fim } ?: continue
        val resolved = ProvidersService.resolve(p, projectBase) { } ?: continue
        if (resolved.protocol != "openai") continue
        if (resolved.apiKey == null && !resolved.isLocal) continue
        return@run resolved to model
      }
      null
    }
    return cached
  }

  private companion object {
    /** Debounce before asking the model for a completion (VibeIDE parity). */
    val DEBOUNCE_DELAY = 250.milliseconds
    /** Lines of prefix/suffix context sent to the model. */
    const val CONTEXT_LINES = 25
    /** Skip completion on very long lines (likely minified/generated). */
    const val MAX_LINE_LENGTH = 500
    /** How long a resolved FIM provider/model is cached before re-resolving. */
    const val TARGET_CACHE_TTL_MS = 30_000L
  }
}
