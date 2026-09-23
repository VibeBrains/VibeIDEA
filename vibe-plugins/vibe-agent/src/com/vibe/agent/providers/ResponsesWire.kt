// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.vibe.agent.util.arr
import com.vibe.agent.util.obj
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * OpenAI's Responses API as a wire of its own: `/v1/responses`, not a dialect of chat/completions.
 *
 * Some models keep abilities only there. GPT-6 Astra calls tools only on this wire, and Sol and Luna combine reasoning
 * with tools only here (developers.openai.com/api/docs/models/gpt-6-sol); a router may serve a model on this endpoint
 * alone. A model declares it with `"protocol": "openai-responses"`, and the value is never guessed: the same model can
 * answer 404 on the other endpoint.
 *
 * Requests are stateless (`store: false`): nothing is kept at the vendor between them, and the reasoning items then
 * carry their `encrypted_content` by default (developers.openai.com/api/docs/guides/reasoning, checked 2026-09-23).
 * What keeps a model's reasoning across tool rounds is the output it returned — reasoning, words and calls — sent back
 * as it came: «pass back all reasoning items, function call items, and function call output items, since the last
 * `user` message» (the same guide).
 *
 * Pure: messages and tools in, the request's parts out; stream events in, their meaning out. Shapes follow the official
 * SDK's types (openai-python, `types/responses`).
 */
object ResponsesWire {
  /**
   * The system prompt, which travels as `instructions`, and the conversation as input items.
   *
   * An assistant message whose output items came from [replayKey] — the same provider and model — goes back as those
   * items; any other goes as its text and calls, the calls without an item id, so nothing pairs them with reasoning
   * that is not there.
   */
  fun input(messages: List<ChatMessage>, replayKey: String): Pair<String, JsonArray> {
    val instructions = messages.filter { it.role == SYSTEM }.joinToString("\n") { it.text }
    val items = ArrayList<JsonElement>()
    for (m in messages) {
      val replay = m.responses?.takeIf { it.key == replayKey }?.items
      when {
        m.role == SYSTEM -> Unit
        m.role == ToolCalls.ROLE -> m.toolResults.forEach { result ->
          items.add(buildJsonObject {
            put("type", FUNCTION_CALL_OUTPUT)
            put("call_id", result.callId)
            put("output", result.text)
          })
        }
        m.role == ASSISTANT && replay != null -> items.addAll(replay)
        m.role == ASSISTANT -> {
          if (m.text.isNotBlank()) items.add(text(ASSISTANT, m.text))
          m.toolCalls.forEach { call ->
            items.add(buildJsonObject {
              put("type", FUNCTION_CALL)
              put("call_id", call.id)
              put("name", call.name)
              put("arguments", call.arguments.ifBlank { "{}" })
            })
          }
        }
        else -> items.add(user(m))
      }
    }
    return instructions to JsonArray(items)
  }

  /** Function tools in this wire's flat shape: no `function` wrapper, and `strict` stated rather than defaulted. */
  fun tools(specs: List<ToolSpec>): JsonArray = JsonArray(specs.map { spec ->
    buildJsonObject {
      put("type", "function")
      put("name", spec.name)
      put("description", spec.description)
      put("parameters", spec.schema)
      // Our schemas are not written for strict mode (every property required, no additional ones), and strict mode
      // would refuse them rather than call the tool.
      put("strict", false)
    }
  })

  /** A piece of the answer's text. */
  fun textDelta(event: JsonObject): String? =
    if (type(event) == "response.output_text.delta") string(event["delta"]) else null

  /** An output item that is complete — the one place a call's arguments and a reasoning item's encrypted content are whole. */
  fun doneItem(event: JsonObject): JsonObject? =
    if (type(event) == "response.output_item.done") event["item"].obj() else null

  /**
   * Why the request failed, when the stream says so: an `error` event or a failed response. A stream that ends on one
   * without a word read as an empty answer that finished.
   */
  fun failure(event: JsonObject): String? = when (type(event)) {
    "error" -> described(event) ?: "error"
    "response.failed" -> event["response"].obj()?.get("error").obj()?.let(::described) ?: "response.failed"
    else -> null
  }

  private fun described(error: JsonObject): String? =
    listOfNotNull(string(error["code"]), string(error["message"])).joinToString(": ").ifEmpty { null }

  /** The output items of a whole answer, from a request that did not stream. */
  fun outputItems(response: JsonObject): List<JsonObject> = response["output"].arr().orEmpty().mapNotNull { it.obj() }

  /** The text of a whole answer: its `output_text` parts in output order. */
  fun outputText(response: JsonObject): String = outputItems(response)
    .filter { string(it["type"]) == MESSAGE }
    .flatMap { it["content"].arr().orEmpty() }
    .mapNotNull { it.obj() }
    .filter { string(it["type"]) == OUTPUT_TEXT }
    .joinToString("") { string(it["text"]).orEmpty() }

  private fun type(event: JsonObject): String? = string(event["type"])

  private fun text(role: String, text: String): JsonObject = buildJsonObject {
    put("role", role)
    put("content", text)
  }

  private fun user(m: ChatMessage): JsonObject = buildJsonObject {
    put("role", m.role)
    if (m.images.isEmpty()) put("content", m.text)
    else put("content", JsonArray(buildList {
      if (m.text.isNotBlank()) add(buildJsonObject { put("type", "input_text"); put("text", m.text) })
      m.images.forEach { image ->
        add(buildJsonObject {
          put("type", "input_image")
          put("image_url", "data:" + image.mimeType + ";base64," + image.base64)
        })
      }
    }))
  }

  internal fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull

  const val FUNCTION_CALL = "function_call"
  internal const val MESSAGE = "message"
  internal const val REASONING = "reasoning"
  internal const val OUTPUT_TEXT = "output_text"
  private const val FUNCTION_CALL_OUTPUT = "function_call_output"
  private const val SYSTEM = "system"
  private const val ASSISTANT = "assistant"
}

/**
 * The output items of one answer on the Responses wire, and whose they are.
 *
 * Sent back verbatim only to the provider and model that produced them ([key]): the encrypted reasoning is that model's
 * own state, and nothing documents that another model or another account takes it. Any other model gets the plain text
 * and calls.
 */
data class ResponsesReplay(val key: String, val items: List<JsonObject>) {
  fun toStored(): JsonObject = buildJsonObject {
    put("key", key)
    put("items", JsonArray(items))
  }

  companion object {
    fun keyOf(providerId: String, modelId: String): String = "$providerId/$modelId"

    /** Tolerant like the rest of the thread: a broken entry is no replay, not a broken thread. */
    fun fromStored(element: JsonElement?): ResponsesReplay? {
      val o = element as? JsonObject ?: return null
      val key = ResponsesWire.string(o["key"]) ?: return null
      val items = (o["items"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
      return if (items.isEmpty()) null else ResponsesReplay(key, items)
    }
  }
}

/**
 * The output items of one streamed answer, in output order, in the shape the wire takes back.
 *
 * Kept from `response.output_item.done` only: the encrypted content of a reasoning item in `output_item.added` may be
 * incomplete (openai-python, `ResponseReasoningItem`). Each item keeps the fields an input item carries and the vendor's
 * own client sends back (openai/codex, `ResponseItem`): what the model reasoned, said and called, with `phase` on its
 * messages — the vendor asks to resend it. Stream bookkeeping (status, annotations, log probabilities) stays behind.
 */
class ResponsesAccumulator {
  private val items = ArrayList<JsonObject>()

  fun item(item: JsonObject) {
    replayable(item)?.let { items.add(it) }
  }

  /**
   * The answer's items, or none when it produced nothing to send back.
   *
   * The vendor pairs items and refuses a broken pair in both directions: a reasoning item without the item it led to,
   * and a call without the reasoning it came from. So an answer cut while reasoning loses its trailing reasoning, and
   * a reasoning item without its encrypted content — nothing is stored at the vendor, so its id names nothing — takes
   * all the answer's reasoning with it, and the ids that tie the rest to it.
   */
  fun items(): List<JsonObject> {
    fun reasoning(item: JsonObject) = ResponsesWire.string(item["type"]) == ResponsesWire.REASONING
    val kept = items.dropLastWhile(::reasoning)
    if (kept.filter(::reasoning).all { !ResponsesWire.string(it["encrypted_content"]).isNullOrEmpty() }) return kept
    return kept.filterNot(::reasoning).map { JsonObject(it - "id") }
  }

  companion object {
    /** The item in its input shape, or null for an item this client does not send back. */
    fun replayable(item: JsonObject): JsonObject? {
      fun keep(vararg keys: String) = JsonObject(item.filterKeys { it in keys })
      return when (ResponsesWire.string(item["type"])) {
        ResponsesWire.REASONING -> keep("type", "id", "summary", "encrypted_content")
        ResponsesWire.FUNCTION_CALL -> keep("type", "id", "call_id", "name", "namespace", "arguments")
        // The refusal part stays: a reasoning item must be followed by the item it led to, and a message dropped for
        // holding no text would leave that reasoning without it — the vendor refuses the pair.
        ResponsesWire.MESSAGE -> {
          val content = item["content"].arr().orEmpty().mapNotNull { it.obj() }.mapNotNull { part ->
            when (ResponsesWire.string(part["type"])) {
              ResponsesWire.OUTPUT_TEXT -> JsonObject(part.filterKeys { it == "type" || it == "text" })
              REFUSAL -> JsonObject(part.filterKeys { it == "type" || it == REFUSAL })
              else -> null
            }
          }
          if (content.isEmpty()) null
          else JsonObject(item.filterKeys { it in setOf("type", "id", "role", "phase") } + ("content" to JsonArray(content)))
        }
        else -> null
      }
    }

    private const val REFUSAL = "refusal"
  }
}
