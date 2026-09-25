// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path

/**
 * Multi round-trip requests of MCP 2026-07-28 (SEP-2322): how a server asks the client for input in the middle of a call
 *
 * The revision removed the server's own requests to the client (`elicitation/create`, `sampling/createMessage`,
 * `roots/list`): the server answers the call with `resultType: "input_required"`, the requests keyed in `inputRequests`
 * and an opaque `requestState`, and the client sends the same call again, with a new id, carrying `inputResponses` under
 * the same keys and the state verbatim
 * The state is the server's: it is never read or rebuilt here, only handed back
 *
 * Pure: the result in, what to ask and what to send again out; who answers is handed in ([Answerer])
 */
object McpInputRequired {
  /** One thing the server asks for: the method that used to come as its own request, and its params */
  data class Request(val key: String, val method: String, val params: JsonObject)

  /** A result that asks for input instead of carrying the tool's output */
  data class Required(val requestState: JsonElement?, val requests: List<Request>)

  /**
   * Who answers the server's requests: the result of [Request.method], or null when this client cannot answer it
   * A request that stays unanswered ends the call: a partial set of answers is not what the server asked for
   */
  fun interface Answerer {
    fun answer(request: Request): JsonElement?

    companion object {
      /** Answers nothing: the call ends with a result that names what the server wanted */
      val NONE = Answerer { null }
    }
  }

  /** `elicitation/create`: a form or a link for the person, answered by the IDE's clarification dialog */
  const val ELICITATION = "elicitation/create"

  /** `roots/list`: the folders the client works in */
  const val ROOTS = "roots/list"

  /**
   * How many times one call may ask for input before it counts as a loop
   * A server may ask again after an answer (a second form after the first), but not forever: each round is a dialog
   */
  const val MAX_ROUNDS = 4

  /** The request for input in [result]; null for a complete result, which a result without `resultType` also is */
  fun of(result: JsonObject): Required? {
    if (result["resultType"]?.jsonPrimitive?.contentOrNull != McpClient.RESULT_INPUT_REQUIRED) return null
    val requests = (result["inputRequests"] as? JsonObject).orEmpty().mapNotNull { (key, element) ->
      val entry = element as? JsonObject ?: return@mapNotNull null
      val method = entry["method"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
      Request(key, method, entry["params"] as? JsonObject ?: JsonObject(emptyMap()))
    }
    return Required(result["requestState"], requests)
  }

  /**
   * The params of the call sent again: the original ones with the answers and the server's state as it came
   * Built from the original each round, so a state the server did not send again does not go back
   * No requests — no `inputResponses`: the server shed load and wants the call again with its state only
   */
  fun retry(original: JsonObject, required: Required, responses: Map<String, JsonElement>): JsonObject =
    JsonObject(original + buildMap {
      if (required.requests.isNotEmpty()) put("inputResponses", JsonObject(responses))
      required.requestState?.let { put("requestState", it) }
    })

  /**
   * The answers to every request, or null when one of them has none
   * A request without a method we know is left unanswered too: [Answerer] decides that, not this function
   */
  fun answers(required: Required, answerer: Answerer): Map<String, JsonElement>? {
    val answers = LinkedHashMap<String, JsonElement>()
    for (request in required.requests) answers[request.key] = answerer.answer(request) ?: return null
    return answers
  }

  /**
   * The params of an MCP elicitation as the clarification dialog reads them
   * MCP makes `mode` optional with the form as the default; the dialog, built for ACP, reads a missing mode as unknown
   */
  fun elicitationParams(params: JsonObject): JsonObject =
    if (params.containsKey(MODE)) params else JsonObject(params + (MODE to JsonPrimitive(FORM)))

  /** The dialog's answer in MCP's words: the outcome is called `action` there, the content is the same */
  fun elicitationResult(dialogAnswer: JsonObject): JsonObject =
    JsonObject(dialogAnswer.mapKeys { (key, _) -> if (key == DIALOG_OUTCOME) ACTION else key })

  private const val MODE = "mode"
  private const val FORM = "form"
  private const val DIALOG_OUTCOME = "outcome"
  private const val ACTION = "action"

  /** The roots answer: the folders the IDE has open for this call */
  fun rootsResult(folders: List<Path>): JsonObject = buildJsonObject {
    put("roots", JsonArray(folders.map { folder ->
      buildJsonObject {
        put("uri", JsonPrimitive(folder.toUri().toString()))
        put("name", JsonPrimitive(folder.fileName?.toString() ?: folder.toString()))
      }
    }))
  }
}
