// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * What a request actually cost in tokens, as the provider itself reported it.
 *
 * Until this existed the direct chat counted a turn by the LENGTH OF THE ANSWER: a request carrying
 * two hundred thousand tokens of context cost, in our report, as much as the sentence it produced.
 * Everything downstream inherited that — the spending ceiling never fired on this path, because
 * there was no money to compare against a limit.
 *
 * Cached input is a separate field and not an accounting detail: on Claude Fable 5.1 a cache read
 * is priced at 0.025 of base input against 0.1 on other Claude models, so a report that folds
 * cache hits into ordinary input can be forty times wrong in the direction that matters.
 */
data class TokenUsage(
  val inputTokens: Long = 0,
  val outputTokens: Long = 0,
  /** Input served from the prompt cache — billed at the cache-read price, not the input price. */
  val cacheReadTokens: Long = 0,
  /** Input written INTO the cache — billed above base input on every provider that charges for it. */
  val cacheWriteTokens: Long = 0,
) {
  val total: Long get() = inputTokens + outputTokens + cacheReadTokens + cacheWriteTokens

  val known: Boolean get() = total > 0

  /**
   * Merges what two events of one response reported.
   *
   * Both wires report usage in pieces — Anthropic sends input at `message_start` and output at
   * `message_delta`; OpenAI sends everything in a final chunk. Taking the maximum per field rather
   * than summing is deliberate: a wire that repeats a running total would otherwise double it.
   */
  fun merge(other: TokenUsage): TokenUsage = TokenUsage(
    inputTokens = maxOf(inputTokens, other.inputTokens),
    outputTokens = maxOf(outputTokens, other.outputTokens),
    cacheReadTokens = maxOf(cacheReadTokens, other.cacheReadTokens),
    cacheWriteTokens = maxOf(cacheWriteTokens, other.cacheWriteTokens),
  )

  companion object {
    val NONE = TokenUsage()

    /**
     * Usage from one Anthropic SSE event, or null when the event carries none.
     *
     * `message_start` carries input, cache reads and cache writes; `message_delta` carries the
     * output count. Both arrive as `usage` objects, so one reader handles both.
     */
    fun fromAnthropicEvent(event: JsonObject): TokenUsage? {
      val usage = event["usage"]?.jsonObject
                  ?: event["message"]?.jsonObject?.get("usage")?.jsonObject
                  ?: return null
      return TokenUsage(
        inputTokens = usage.long("input_tokens"),
        outputTokens = usage.long("output_tokens"),
        cacheReadTokens = usage.long("cache_read_input_tokens"),
        cacheWriteTokens = usage.long("cache_creation_input_tokens"),
      ).takeIf { it.known }
    }

    /**
     * Usage from an OpenAI-compatible chunk, or null when the chunk carries none.
     *
     * Only the final chunk has it, and only when the request asked for it — see
     * [LlmClient] for the `stream_options` flag. `prompt_tokens` INCLUDES the cached part on this
     * wire, so the cached count is subtracted out to keep the fields disjoint the way the
     * Anthropic wire already reports them; otherwise the same tokens would be priced twice.
     */
    fun fromOpenAiChunk(chunk: JsonObject): TokenUsage? {
      val usage = chunk["usage"]?.jsonObject ?: return null
      val cached = usage["prompt_tokens_details"]?.jsonObject?.long("cached_tokens") ?: 0
      val prompt = usage.long("prompt_tokens")
      return TokenUsage(
        inputTokens = (prompt - cached).coerceAtLeast(0),
        outputTokens = usage.long("completion_tokens"),
        cacheReadTokens = cached,
      ).takeIf { it.known }
    }

    private fun JsonObject.long(name: String): Long = this[name]?.jsonPrimitive?.longOrNull ?: 0
  }
}
