// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.vibe.agent.util.arr
import com.vibe.agent.util.obj
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Why the model stopped, as the provider said it — so an answer that ended abnormally does not pass for a finished one.
 *
 * Every wire reports it, and for a long time none of our readers looked. A classifier refusal on Claude is an HTTP 200
 * with `stop_reason: "refusal"` and often no text at all; a length cut is a normal-looking answer with its end missing;
 * MiMo's `repetition_truncation` stops a model that went in circles. In the chat all three read as «the model
 * answered», which is exactly what they are not.
 *
 * Pure: a stream event or a response body in, a reason out; null when the event says nothing about stopping.
 */
data class StopReason(
  val kind: Kind,
  /** The vendor's own word, kept for the line that names a reason we do not know. */
  val raw: String,
  /** The policy area of a refusal, when the vendor named one; null is a normal value, not a placeholder. */
  val category: String? = null,
  /** The vendor's human-readable account of a refusal; unstable text, shown rather than parsed. */
  val explanation: String? = null,
  /** A refusal's one-time credit for the retry on another model ([FallbackCredit]); null when none was minted */
  val creditToken: String? = null,
) {
  enum class Kind {
    /** The model finished its answer. */
    END,

    /** The model stopped to call tools; the loop goes on. */
    TOOL_USE,

    /** The output limit cut the answer. */
    LENGTH,

    /** A safety classifier declined the request. */
    REFUSAL,

    /** The vendor's content filter stopped the answer. */
    CONTENT_FILTER,

    /** The vendor cut a model that started repeating itself. */
    REPETITION,

    /** Anything else the vendor named: shown as it is rather than guessed at. */
    OTHER,
  }

  /** Whether the person must be told: the answer ended for a reason other than finishing or calling tools. */
  val abnormal: Boolean get() = kind != Kind.END && kind != Kind.TOOL_USE

  companion object {
    /**
     * A `message_delta` event of the Anthropic stream, or a whole Anthropic response.
     *
     * The stream says it at the end, in `delta`; a response body says it at the top level. `message_start` carries
     * `stop_reason: null` and is skipped like any other event.
     */
    fun fromAnthropicEvent(event: JsonObject): StopReason? {
      val holder = if (string(event["type"]) == "message_delta") event["delta"].obj() ?: return null else event
      val raw = string(holder["stop_reason"]) ?: return null
      val details = holder["stop_details"].obj() ?: event["stop_details"].obj()
      val kind = when (raw) {
        "end_turn", "stop_sequence" -> Kind.END
        "tool_use" -> Kind.TOOL_USE
        "max_tokens" -> Kind.LENGTH
        "refusal" -> Kind.REFUSAL
        // The Anthropic-compatible routes of other vendors add their own words here (MiMo documents both).
        "content_filter" -> Kind.CONTENT_FILTER
        "repetition_truncation" -> Kind.REPETITION
        else -> Kind.OTHER
      }
      return StopReason(kind, raw, string(details?.get("category")), string(details?.get("explanation")),
                        string(details?.get(FallbackCredit.FIELD)))
    }

    /** The chunk of a chat/completions stream that carries `finish_reason`, or a whole chat/completions response. */
    fun fromOpenAiChunk(chunk: JsonObject): StopReason? {
      val raw = string(chunk["choices"].arr()?.firstOrNull().obj()?.get("finish_reason")) ?: return null
      val kind = when (raw) {
        "stop" -> Kind.END
        "tool_calls", "function_call" -> Kind.TOOL_USE
        "length" -> Kind.LENGTH
        "content_filter" -> Kind.CONTENT_FILTER
        "repetition_truncation" -> Kind.REPETITION
        else -> Kind.OTHER
      }
      return StopReason(kind, raw)
    }

    /**
     * The event that closes a Responses stream: `response.completed` or `response.incomplete`.
     *
     * This wire has no finish reason. An answer either completes or is incomplete with `incomplete_details.reason`,
     * and a refusal is not a way of stopping but a `refusal` part in place of the text — which, unread, is an empty
     * answer that looks finished.
     */
    fun fromResponsesEvent(event: JsonObject): StopReason? = when (string(event["type"])) {
      "response.completed" -> event["response"].obj()?.let { fromResponse(it, incomplete = false) }
      "response.incomplete" -> event["response"].obj()?.let { fromResponse(it, incomplete = true) }
      else -> null
    }

    /** A whole Responses answer, from a request that did not stream. */
    fun fromResponsesBody(response: JsonObject): StopReason? = when (string(response["status"])) {
      "completed" -> fromResponse(response, incomplete = false)
      "incomplete" -> fromResponse(response, incomplete = true)
      else -> null
    }

    private fun fromResponse(response: JsonObject, incomplete: Boolean): StopReason {
      val output = response["output"].arr().orEmpty().mapNotNull { it.obj() }
      val refusal = output.filter { string(it["type"]) == "message" }
        .flatMap { it["content"].arr().orEmpty() }
        .mapNotNull { it.obj() }
        .firstOrNull { string(it["type"]) == "refusal" }
      if (refusal != null) return StopReason(Kind.REFUSAL, "refusal", explanation = string(refusal["refusal"]))
      if (incomplete) {
        val reason = string(response["incomplete_details"].obj()?.get("reason"))
        val kind = when (reason) {
          "max_output_tokens" -> Kind.LENGTH
          "content_filter" -> Kind.CONTENT_FILTER
          else -> Kind.OTHER
        }
        return StopReason(kind, reason ?: "incomplete")
      }
      return StopReason(if (output.any { string(it["type"]) == ResponsesWire.FUNCTION_CALL }) Kind.TOOL_USE else Kind.END, "completed")
    }

    /** An event of the Gemini stream that carries `finishReason`. */
    fun fromGeminiEvent(event: JsonObject): StopReason? {
      val raw = string(event["candidates"].arr()?.firstOrNull().obj()?.get("finishReason")) ?: return null
      val kind = when (raw) {
        // Gemini says STOP after a function call too; the tool loop reads the calls themselves, not this word.
        "STOP" -> Kind.END
        "MAX_TOKENS" -> Kind.LENGTH
        "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION", "IMAGE_SAFETY" -> Kind.CONTENT_FILTER
        "FINISH_REASON_UNSPECIFIED" -> return null
        else -> Kind.OTHER
      }
      return StopReason(kind, raw)
    }

    private fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
  }
}
