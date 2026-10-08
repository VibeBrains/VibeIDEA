// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A reasoning effort changed mid-conversation without breaking the prompt cache
 * ([ModelQuirks.Quirk.EFFORT_BY_UPDATE], [ModelQuirks.Quirk.EFFORT_BY_SYSTEM_MESSAGE])
 *
 * The request-level effort is part of the prefix the cache matches: moving the slider mid-thread rewrote it,
 * and everything after was billed as fresh input
 * The vendors' way keeps the effort of the thread's first request and puts an update before the user message where it changed:
 * Responses: a `configuration_update` item (developers.openai.com/api/docs/guides/reasoning#change-reasoning-mid-conversation)
 * Anthropic: a system message with no content and `output_config.effort`, behind a beta header
 * (platform.claude.com/docs/en/build-with-claude/effort#change-effort-mid-conversation-beta)
 *
 * History travels whole with every request here, and the vendor asks to replay each update at its original place:
 * an answer remembers the effort its request was sent at ([ChatMessage.effortMark], `provider/model#effort`), and the
 * plan puts every update back before the user message that answer followed
 *
 * Pure: the messages, the model's key and the effort asked now in, the messages to send and the request's effort out
 */
object EffortUpdates {
  /** A message standing for the `configuration_update` item; its text is the effort ([ResponsesWire.input]) */
  const val ROLE = "configuration_update"

  /** The request's effort, the messages with the updates in place, and the mark the answer is stored with */
  data class Plan(val requestEffort: String, val messages: List<ChatMessage>, val mark: String)

  fun mark(key: String, effort: String): String = "$key$SEPARATOR$effort"

  fun plan(messages: List<ChatMessage>, key: String, current: String): Plan {
    val prefix = key + SEPARATOR
    val lastUser = messages.indexOfLast { it.role == USER }
    // The effort each earlier user message was answered at: the mark of the answer that followed it, for this model
    val answeredAt = HashMap<Int, String>()
    var pendingUser = -1
    messages.forEachIndexed { index, m ->
      when {
        m.role == USER -> pendingUser = index
        m.role == ASSISTANT && pendingUser >= 0 && m.effortMark?.startsWith(prefix) == true -> {
          answeredAt[pendingUser] = m.effortMark.removePrefix(prefix)
          pendingUser = -1
        }
      }
    }
    val base = answeredAt.toSortedMap().values.firstOrNull() ?: current
    var effective = base
    val planned = ArrayList<ChatMessage>(messages.size + 2)
    messages.forEachIndexed { index, m ->
      val effort = if (index == lastUser) current else answeredAt[index]
      if (m.role == USER && effort != null && effort != effective) {
        planned += ChatMessage(ROLE, effort)
        effective = effort
      }
      planned += m
    }
    return Plan(base, planned, mark(key, current))
  }

  /** The beta header the Anthropic spelling needs; without it the field is a 400 «Extra inputs are not permitted» */
  const val ANTHROPIC_BETA = "mid-conversation-output-config-2026-07-01"

  /**
   * Whether an Anthropic request may carry updates: the vendor's own API and a model of [ModelQuirks.Quirk.EFFORT_BY_SYSTEM_MESSAGE]
   * Other addresses on the same wire never said they take the field, and a refused field fails the whole turn
   */
  fun anthropicSupported(baseUrl: String, quirkModelId: String, overrides: List<ModelQuirks.Rule> = emptyList()): Boolean =
    AnthropicApi.official(baseUrl) && ModelQuirks.has(quirkModelId, ModelQuirks.Quirk.EFFORT_BY_SYSTEM_MESSAGE, overrides)

  /**
   * The effort an Anthropic request can move by an update, from its reasoning fields with `extraBody` laid over them
   * Null outside adaptive thinking: with `between_tools` (Sonnet 5.5) or `disabled` (Haiku 5.5) the vendor refuses any
   * mid-conversation change with a 400, and a changed thinking mode restarts the cache anyway
   */
  fun anthropicEffort(fields: JsonObject): String? {
    val type = ((fields[THINKING] as? JsonObject)?.get(TYPE))?.jsonPrimitive?.contentOrNull
    if (type != null && type != ADAPTIVE) return null
    return ((fields[OUTPUT_CONFIG] as? JsonObject)?.get(EFFORT))?.jsonPrimitive?.contentOrNull
  }

  /** The Anthropic spelling of an update: the new level holds from the next user turn until a later update */
  fun anthropicMessage(effort: String): JsonObject = buildJsonObject {
    put("role", SYSTEM)
    put("content", JsonArray(emptyList()))
    put(OUTPUT_CONFIG, buildJsonObject { put(EFFORT, effort) })
  }

  private const val SEPARATOR = "#"
  private const val SYSTEM = "system"
  private const val THINKING = "thinking"
  private const val TYPE = "type"
  private const val ADAPTIVE = "adaptive"
  private const val OUTPUT_CONFIG = "output_config"
  private const val EFFORT = "effort"
  private const val USER = "user"
  private const val ASSISTANT = "assistant"
}
