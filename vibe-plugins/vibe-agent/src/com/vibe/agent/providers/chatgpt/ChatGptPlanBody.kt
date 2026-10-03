// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.providers.chatgpt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A Responses request shaped for the ChatGPT plan, the last step before it goes
 *
 * The plan's route is a preview with its own rules (developers.openai.com/siwc/token-sharing-open-source/preview-limitations,
 * checked 01.10.2026): every request streams and stores nothing, a list of fields is refused, `previous_response_id`
 * is not for HTTP, and function tools come grouped in a namespace. Applied after the quirks and the model's `extraBody`,
 * so a field written by hand does not break the route either; what is taken out is named, not dropped silently
 *
 * The list is the vendor's preview contract and will change; the shared vectors (`testVectors/chatgptPlan.json`)
 * hold it together with VibeIDE
 *
 * Pure: the body in, the shaped body and the fields taken out of it out
 */
object ChatGptPlanBody {
  /** Fields the plan's route refuses */
  val REFUSED: Set<String> = setOf(
    "background", "conversation", "max_output_tokens", "max_tool_calls", "metadata", "moderation", "multi_agent",
    "previous_response_id", "prompt", "prompt_cache_retention", "safety_identifier", "temperature", "top_logprobs",
    "top_p", "truncation", "user",
  )

  /** The namespace the IDE's function tools are grouped in */
  const val NAMESPACE = "vibe"

  data class Shaped(val body: JsonObject, val removed: List<String>)

  fun shape(body: JsonObject, namespaceDescription: String): Shaped {
    val removed = body.keys.filter { it in REFUSED }.sorted()
    val fields = LinkedHashMap(body.filterKeys { it !in REFUSED })
    fields[STORE] = JsonPrimitive(false)
    fields[STREAM] = JsonPrimitive(true)
    (fields[TOOLS] as? JsonArray)?.let { fields[TOOLS] = grouped(it, namespaceDescription) }
    (fields[INPUT] as? JsonArray)?.let { fields[INPUT] = JsonArray(it.map { item -> namespaced(item as? JsonObject) ?: item }) }
    return Shaped(JsonObject(fields), removed)
  }

  /** Function tools in one namespace; tools of another type stay as they were */
  private fun grouped(tools: JsonArray, description: String): JsonArray {
    val functions = tools.filter { it.type() == FUNCTION }
    if (functions.isEmpty()) return tools
    val namespace = JsonObject(mapOf(
      "type" to JsonPrimitive(NAMESPACE_TYPE),
      "name" to JsonPrimitive(NAMESPACE),
      "description" to JsonPrimitive(description),
      TOOLS to JsonArray(functions),
    ))
    return JsonArray(listOf(namespace) + tools.filter { it.type() != FUNCTION })
  }

  /** A call of the history names the namespace its tool lives in now; one the vendor sent back names it already */
  private fun namespaced(item: JsonObject?): JsonObject? {
    if (item == null || item.type() != FUNCTION_CALL || NAMESPACE_FIELD in item) return null
    return JsonObject(item + (NAMESPACE_FIELD to JsonPrimitive(NAMESPACE)))
  }

  private fun kotlinx.serialization.json.JsonElement.type(): String? =
    ((this as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull

  private const val STORE = "store"
  private const val STREAM = "stream"
  private const val TOOLS = "tools"
  private const val INPUT = "input"
  private const val FUNCTION = "function"
  private const val FUNCTION_CALL = "function_call"
  private const val NAMESPACE_TYPE = "namespace"
  private const val NAMESPACE_FIELD = "namespace"
}
