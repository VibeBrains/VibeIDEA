// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.hooks

import com.vibe.agent.i18n.VibeI18n.t

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Pure parser for `.vibe/hooks.json`, VibeIDE contract verbatim (no IO here).
 * A malformed single hook is dropped whole with a reason, never applied half —
 * a broken policy must not silently degrade into a different one.
 */
enum class HookEvent(val wire: String) {
  PRE_TOOL_USE("preToolUse"),
  POST_TOOL_USE("postToolUse"),
  TURN_END("turnEnd");

  companion object {
    fun fromWire(s: String?): HookEvent? = entries.firstOrNull { it.wire == s }
  }
}

data class Hook(
  val event: HookEvent,
  val command: String,
  /** Exact tool names; empty = any tool. Ignored for [HookEvent.TURN_END]. */
  val tools: List<String>,
  val timeoutMs: Long,
  val label: String?,
) {
  /** Human name in messages: label if set, else the command. */
  fun name(): String = label ?: command
}

object HookConfig {
  const val DEFAULT_TIMEOUT_MS = 30_000L
  const val MAX_TIMEOUT_MS = 300_000L

  /**
   * Parse the whole file. Returns the valid hooks in file order; each problem is
   * reported through [onWarning]. A top-level shape error yields an empty list.
   */
  fun parse(text: String, onWarning: (String) -> Unit): List<Hook> {
    val root = try {
      Json { ignoreUnknownKeys = true }.parseToJsonElement(text)
    }
    catch (e: Exception) {
      onWarning(t("hooks.warn.badJson", "reason" to e.message))
      return emptyList()
    }
    val obj = root as? JsonObject ?: run {
      onWarning(t("hooks.warn.noArray"))
      return emptyList()
    }
    val array = obj["hooks"] as? JsonArray ?: run {
      onWarning(t("hooks.warn.noArray"))
      return emptyList()
    }
    val result = ArrayList<Hook>()
    for ((i, element) in array.withIndex()) {
      val hookObj = element as? JsonObject ?: run { onWarning(t("hooks.warn.notObject", "index" to (i + 1))); continue }
      val event = HookEvent.fromWire(hookObj["event"]?.jsonPrimitive?.contentOrNull) ?: run {
        onWarning(t("hooks.warn.unknownEvent", "index" to (i + 1)))
        continue
      }
      val command = hookObj["command"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: run {
        onWarning(t("hooks.warn.noCommand", "index" to (i + 1)))
        continue
      }
      var tools = (hookObj["tools"] as? JsonArray).orEmptyList().mapNotNull { it.jsonPrimitive.contentOrNull }
      if (event == HookEvent.TURN_END && tools.isNotEmpty()) {
        onWarning(t("hooks.warn.turnEndTools", "hook" to (hookObj["label"]?.jsonPrimitive?.contentOrNull ?: command)))
        tools = emptyList()
      }
      val timeoutMs = when (val raw = hookObj["timeoutMs"]?.jsonPrimitive?.longOrNull) {
        null -> DEFAULT_TIMEOUT_MS
        in 1..MAX_TIMEOUT_MS -> raw
        else -> if (raw > MAX_TIMEOUT_MS) {
          onWarning(t("hooks.warn.timeoutClamped", "value" to raw, "max" to MAX_TIMEOUT_MS))
          MAX_TIMEOUT_MS
        } else DEFAULT_TIMEOUT_MS
      }
      result.add(Hook(event, command, tools, timeoutMs, hookObj["label"]?.jsonPrimitive?.contentOrNull))
    }
    return result
  }

  /** Hooks for one event×tool, in file order. Empty [tools] matches any tool. */
  fun hooksFor(hooks: List<Hook>, event: HookEvent, tool: String?): List<Hook> =
    hooks.filter { it.event == event && (event == HookEvent.TURN_END || it.tools.isEmpty() || (tool != null && tool in it.tools)) }

  private fun JsonArray?.orEmptyList(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
