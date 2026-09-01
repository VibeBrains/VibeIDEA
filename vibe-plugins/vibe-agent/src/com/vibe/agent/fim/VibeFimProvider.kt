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
import com.vibe.agent.settings.VibeAgentSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * FIM autocomplete fed by providers.json models marked `fim: true` (openai protocol).
 *
 * The provider itself is only wiring: what to ask for lives in [FimPrediction], what to keep of the
 * answer in [FimFilters], what not to ask twice in [FimCache], and how it is all doing in
 * [FimMetrics]. Keeping those pure is what makes an autocomplete testable at all — the editor path
 * is coroutines, documents and carets, none of which can be asserted about cheaply.
 */
class VibeFimProvider : DebouncedInlineCompletionProvider() {
  override val id: InlineCompletionProviderID = InlineCompletionProviderID("com.vibe.agent.fim")
  /**
   * Built per request rather than once: the quirk catalogue is per project, and one shared client
   * would answer with whichever project happened to open first.
   */
  private fun llmFor(projectBase: String?) = LlmClient(projectBase = projectBase)

  @Volatile private var cached: Pair<ResolvedProvider, ModelEntry>? = null
  @Volatile private var cachedAt: Long = 0
  @Volatile private var lastServed: FimPrediction.Served? = null

  override suspend fun getDebounceDelay(request: InlineCompletionRequest): Duration =
    VibeAgentSettings.fimDebounceMs.milliseconds

  override fun isEnabled(event: InlineCompletionEvent): Boolean =
    VibeAgentSettings.fimEnabled &&
    (event is InlineCompletionEvent.DocumentChange || event is InlineCompletionEvent.DirectCall)

  override suspend fun getSuggestionDebounced(request: InlineCompletionRequest): InlineCompletionSuggestion {
    val project = request.editor.project ?: return InlineCompletionSuggestion.Empty
    val target = withContext(Dispatchers.IO) { fimTarget(project.basePath) } ?: return InlineCompletionSuggestion.Empty
    val document = request.document
    val offset = request.endOffset
    val text = document.charsSequence.toString()

    val lineStart = text.lastIndexOf('\n', maxOf(0, offset - 1)).let { if (it < 0) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
    val lineBefore = text.substring(lineStart, offset)
    val lineAfter = text.substring(offset, lineEnd)
    // A minified or generated line: nothing useful to continue, and a huge prompt to pay for.
    if (lineBefore.length > MAX_LINE_LENGTH) return InlineCompletionSuggestion.Empty

    val window = FimPrediction.contextLines(target.first.isLocal)
    val prefix = FimPrediction.limitPrefix(text.substring(0, offset), window)
    val suffix = FimPrediction.limitSuffix(text.substring(offset), window)
    val path = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(document)?.path.orEmpty()
    val justAccepted = FimPrediction.wasJustAccepted(lastServed, path, offset, text.substring(0, offset))

    val plan = FimPrediction.plan(prefix, suffix, lineBefore, lineAfter, justAccepted)
    if (!plan.shouldGenerate) {
      // Refusals are counted: they are the cheapest win this feature has, and invisible otherwise.
      metrics.refusedToPredict()
      return InlineCompletionSuggestion.Empty
    }

    val line = document.getLineNumber(offset.coerceIn(0, document.textLength))
    val key = FimCache.key(path, line, offset - lineStart, plan.prefix)
    cache.get(key)?.let { hit ->
      lastServed = FimPrediction.Served(path, offset + hit.length, hit)
      return InlineCompletionSingleSuggestion.build { emit(InlineCompletionGrayTextElement(hit)) }
    }

    val startedAt = System.currentTimeMillis()
    val raw = withContext(Dispatchers.IO) {
      runCatching { llmFor(project.basePath).fimComplete(target.first, target.second, plan.prefix, plan.suffix, plan.stop) }
        .onFailure { metrics.failure() }
        .getOrNull()
    } ?: return InlineCompletionSuggestion.Empty

    val completion = FimFilters.trimEdges(FimFilters.clean(raw.trimEnd('\n')))
    metrics.answered(System.currentTimeMillis() - startedAt, completion.isNotBlank())
    if (completion.isBlank()) return InlineCompletionSuggestion.Empty
    cache.put(key, completion)
    lastServed = FimPrediction.Served(path, offset + completion.length, completion)
    return InlineCompletionSingleSuggestion.build { emit(InlineCompletionGrayTextElement(completion)) }
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

  companion object {
    /** Shared with the metrics action: one autocomplete, one set of numbers. */
    val cache = FimCache(VibeAgentSettings.fimCacheSize)
    val metrics = FimMetrics()

    /** Skip completion on very long lines (likely minified/generated). */
    private const val MAX_LINE_LENGTH = 500

    /** How long a resolved FIM provider/model is cached before re-resolving. */
    private const val TARGET_CACHE_TTL_MS = 30_000L
  }
}
