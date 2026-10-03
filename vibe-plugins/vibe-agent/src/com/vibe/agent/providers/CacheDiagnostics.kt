// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Anthropic's own account of a prompt-cache miss: what differed from the previous request, and what it cost
 *
 * The chat already says when its own prefix stopped being append-only ([com.vibe.agent.history.WirePrefix]), but it
 * sees only its side: a changed model, an expired entry or a server-side reason are invisible from here
 * The request names the previous response's id; the answer names the reason and the input tokens that were not read
 * from the cache (anthropic-sdk-python 22062b8, «cache diagnostics GA», checked 2026-09-30)
 *
 * Pure: the previous id in, the request field out; a stream event in, the id and the reason out
 */
object CacheDiagnostics {
  /** Asked for on this request; [previousMessageId] null on a thread's first request opts in without a comparison */
  data class Ask(val previousMessageId: String?)

  /** Why the cache missed; [tokens] is the vendor's estimate of input it would have read, null when it gives none */
  data class Miss(val reason: String, val tokens: Long?)

  const val FIELD = "diagnostics"

  /** Reasons that name a change between the two requests; the others say only that nothing could be compared */
  val CHANGES = setOf("model_changed", "system_changed", "tools_changed", "messages_changed")

  fun field(ask: Ask): JsonObject = buildJsonObject {
    put("previous_message_id", ask.previousMessageId?.let { JsonPrimitive(it) } ?: JsonNull)
  }

  /** The response's id, carried by `message_start` in a stream and at the top of a whole answer */
  fun messageId(event: JsonObject): String? =
    ((event["message"] as? JsonObject) ?: event.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "message" })
      ?.get("id")?.jsonPrimitive?.contentOrNull

  /**
   * The miss the event reports, wherever it rides: the message of `message_start`, the delta or the top of
   * `message_delta`, or a whole answer; null when the event says nothing or the comparison is still pending
   */
  fun miss(event: JsonObject): Miss? {
    val holder = listOfNotNull(event["message"] as? JsonObject, event["delta"] as? JsonObject, event)
      .firstNotNullOfOrNull { it[FIELD] as? JsonObject } ?: return null
    val reason = holder["cache_miss_reason"] as? JsonObject ?: return null
    val type = reason["type"]?.jsonPrimitive?.contentOrNull ?: return null
    return Miss(type, reason["cache_missed_input_tokens"]?.jsonPrimitive?.longOrNull)
  }

  /** Worth a line in the feed: a change the person can act on, and input that was paid for again */
  fun worthSaying(miss: Miss?): Boolean = miss != null && miss.reason in CHANGES && (miss.tokens ?: 0) > 0
}
