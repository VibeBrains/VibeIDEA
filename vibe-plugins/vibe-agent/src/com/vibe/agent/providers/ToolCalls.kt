// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A tool offered to the model: the name it calls, a sentence, and a JSON schema of its arguments. */
data class ToolSpec(val name: String, val description: String, val schema: JsonObject)

/** One call the model asked for. [arguments] is the raw JSON text as the wire delivered it. */
data class ToolCall(val id: String, val name: String, val arguments: String) {
  /** The arguments as an object; a model that sent nothing or broken JSON gets an empty object. */
  fun argumentsObject(): JsonObject =
    runCatching { Json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject }.getOrDefault(JsonObject(emptyMap()))
}

/** The answer to one [ToolCall]. [name] travels too: Gemini matches a result by name, not by id. */
data class ToolResult(val callId: String, val name: String, val text: String, val isError: Boolean = false)

/**
 * Tool calling on the three wires the direct chat speaks, kept free of transport so it is unit-testable.
 *
 * The three differ in everything except the idea:
 * - openai: `tools[].function`, calls in `delta.tool_calls` streamed by index, one `role: tool` message per result
 * - anthropic: `tools[].input_schema`, calls as `tool_use` blocks with `input_json_delta`, results as
 *   `tool_result` blocks in ONE user message — two consecutive user messages are rejected
 * - gemini: `functionDeclarations`, calls as whole `functionCall` parts without ids, results as
 *   `functionResponse` parts matched by name
 *
 * That is why a round's results live in one [ChatMessage] (role [ROLE]): two of three wires need them
 * together, and the third expands them.
 */
object ToolCalls {
  /** Role of the message carrying a round's [ToolResult]s. */
  const val ROLE = "tool"

  fun openAiTools(tools: List<ToolSpec>): JsonArray = JsonArray(tools.map { tool ->
    buildJsonObject {
      put("type", "function")
      put("function", buildJsonObject {
        put("name", tool.name)
        put("description", tool.description)
        put("parameters", tool.schema)
      })
    }
  })

  fun anthropicTools(tools: List<ToolSpec>): JsonArray = JsonArray(tools.map { tool ->
    buildJsonObject {
      put("name", tool.name)
      put("description", tool.description)
      put("input_schema", tool.schema)
    }
  })

  fun geminiTools(tools: List<ToolSpec>): JsonArray = JsonArray(listOf(buildJsonObject {
    put("functionDeclarations", JsonArray(tools.map { tool ->
      buildJsonObject {
        put("name", tool.name)
        put("description", tool.description)
        put("parameters", geminiSchema(tool.schema))
      }
    }))
  }))

  /** Gemini accepts an OpenAPI subset and rejects the whole request on `$schema` or `additionalProperties`. */
  private fun geminiSchema(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.filterKeys { it !in GEMINI_REJECTED_KEYS }.mapValues { geminiSchema(it.value) })
    is JsonArray -> JsonArray(element.map(::geminiSchema))
    else -> element
  }

  private val GEMINI_REJECTED_KEYS = setOf("\$schema", "additionalProperties")

  /** openai: the assistant's calls, attached to its message. */
  fun openAiCalls(calls: List<ToolCall>): JsonArray = JsonArray(calls.map { call ->
    buildJsonObject {
      put("id", call.id)
      put("type", "function")
      put("function", buildJsonObject {
        put("name", call.name)
        put("arguments", call.arguments.ifBlank { "{}" })
      })
    }
  })

  /** openai: one `role: tool` message per result. */
  fun openAiResults(m: ChatMessage): List<JsonObject> = m.toolResults.map { result ->
    buildJsonObject {
      put("role", "tool")
      put("tool_call_id", result.callId)
      put("content", result.text)
    }
  }

  /** anthropic: the assistant's text (if any) followed by its `tool_use` blocks. */
  fun anthropicAssistant(m: ChatMessage): JsonObject = buildJsonObject {
    put("role", "assistant")
    put("content", JsonArray(buildList {
      if (m.text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", m.text) })
      m.toolCalls.forEach { call ->
        add(buildJsonObject {
          put("type", "tool_use")
          put("id", call.id)
          put("name", call.name)
          put("input", call.argumentsObject())
        })
      }
    }))
  }

  /** anthropic: every result of the round in one user message. */
  fun anthropicResults(m: ChatMessage): JsonObject = buildJsonObject {
    put("role", "user")
    put("content", JsonArray(m.toolResults.map { result ->
      buildJsonObject {
        put("type", "tool_result")
        put("tool_use_id", result.callId)
        put("content", result.text)
        if (result.isError) put("is_error", true)
      }
    }))
  }

  /** gemini: the model's text (if any) followed by its `functionCall` parts. */
  fun geminiAssistant(m: ChatMessage): JsonObject = buildJsonObject {
    put("role", "model")
    put("parts", JsonArray(buildList {
      if (m.text.isNotBlank()) add(buildJsonObject { put("text", m.text) })
      m.toolCalls.forEach { call ->
        add(buildJsonObject {
          put("functionCall", buildJsonObject { put("name", call.name); put("args", call.argumentsObject()) })
        })
      }
    }))
  }

  /** gemini: every result of the round as `functionResponse` parts of one user turn. */
  fun geminiResults(m: ChatMessage): JsonObject = buildJsonObject {
    put("role", "user")
    put("parts", JsonArray(m.toolResults.map { result ->
      buildJsonObject {
        put("functionResponse", buildJsonObject {
          put("name", result.name)
          put("response", buildJsonObject { put(if (result.isError) "error" else "content", result.text) })
        })
      }
    }))
  }

  /** The blank content of an assistant message that only calls tools: null, not an empty string. */
  val NO_CONTENT: JsonElement = JsonNull
}

/**
 * Collects the calls of one answer as the stream delivers them.
 *
 * openai and anthropic send a call in pieces — the name first, the arguments as fragments of JSON text —
 * keyed by the position of the call in the answer; gemini sends each call whole. One collector per request.
 */
class ToolCallAccumulator {
  private class Pending(var id: String? = null, var name: String? = null, val arguments: StringBuilder = StringBuilder())

  private val byIndex = java.util.TreeMap<Int, Pending>()
  private var geminiCount = 0

  fun openAiChunk(chunk: JsonObject) {
    val delta = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta") as? JsonObject ?: return
    openAiCalls(delta["tool_calls"] as? JsonArray)
  }

  /** A model that refuses to stream answers with a whole `message`. */
  fun openAiMessage(message: JsonObject) = openAiCalls(message["tool_calls"] as? JsonArray)

  private fun openAiCalls(calls: JsonArray?) {
    calls?.forEachIndexed { position, element ->
      val call = element as? JsonObject ?: return@forEachIndexed
      val pending = byIndex.getOrPut(call["index"]?.jsonPrimitive?.intOrNull ?: position) { Pending() }
      call["id"]?.jsonPrimitive?.contentOrNull?.let { pending.id = it }
      val function = call["function"] as? JsonObject
      function?.get("name")?.jsonPrimitive?.contentOrNull?.let { pending.name = it }
      function?.get("arguments")?.jsonPrimitive?.contentOrNull?.let { pending.arguments.append(it) }
    }
  }

  fun anthropicEvent(event: JsonObject) {
    val index = event["index"]?.jsonPrimitive?.intOrNull ?: return
    when (event["type"]?.jsonPrimitive?.contentOrNull) {
      "content_block_start" -> {
        val block = event["content_block"] as? JsonObject ?: return
        if (block["type"]?.jsonPrimitive?.contentOrNull != "tool_use") return
        byIndex[index] = Pending(
          id = block["id"]?.jsonPrimitive?.contentOrNull,
          name = block["name"]?.jsonPrimitive?.contentOrNull,
        )
      }
      "content_block_delta" -> {
        val delta = event["delta"] as? JsonObject ?: return
        if (delta["type"]?.jsonPrimitive?.contentOrNull != "input_json_delta") return
        delta["partial_json"]?.jsonPrimitive?.contentOrNull?.let { byIndex[index]?.arguments?.append(it) }
      }
    }
  }

  fun geminiEvent(event: JsonObject) {
    val parts = event["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
      ?.get("content")?.jsonObject?.get("parts") as? JsonArray ?: return
    for (part in parts) {
      val call = (part as? JsonObject)?.get("functionCall") as? JsonObject ?: continue
      // Gemini gives no id; a stable one per answer is enough to pair the result with its call.
      val position = geminiCount++
      byIndex[position] = Pending(
        id = "gemini-$position",
        name = call["name"]?.jsonPrimitive?.contentOrNull,
      ).also { it.arguments.append((call["args"] as? JsonObject ?: JsonObject(emptyMap())).toString()) }
    }
  }

  /** Complete calls in answer order; a fragment without a name is not a call. */
  fun calls(): List<ToolCall> = byIndex.entries.mapNotNull { (index, pending) ->
    val name = pending.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    ToolCall(id = pending.id ?: "call-$index", name = name, arguments = pending.arguments.toString())
  }
}
