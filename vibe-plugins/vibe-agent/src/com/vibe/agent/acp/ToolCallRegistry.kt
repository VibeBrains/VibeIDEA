// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * One agent tool-call, assembled across `tool_call` (announce) and one or more
 * `tool_call_update` frames. VibeIDEA needs this registry because — unlike
 * VibeIDE, where the agent loop lives in the client — here the loop runs inside
 * Claude Code and the only window onto it is the `session/update` stream keyed
 * by `toolCallId`. Hooks, audit, the terminal stream and the fuses all read from
 * this registry.
 */
data class ToolCall(
  val id: String,
  var title: String,
  var kind: String?,
  var status: String,
  var toolName: String?,
  var rawInput: JsonObject?,
  /** Terminal id carried by the Claude adapter's `_meta.terminal_info`, if any. */
  var terminalId: String? = null,
  /** ACP `locations` — file paths the tool works with (used to widen changed-file coverage). */
  var locations: List<String> = emptyList(),
) {
  val isRunning: Boolean get() = status == STATUS_PENDING || status == STATUS_IN_PROGRESS
  val isDone: Boolean get() = status == STATUS_COMPLETED || status == STATUS_FAILED

  /** Raw params as `name → string value` for the privacy filter and hook payload. */
  fun rawParamsFlat(): Map<String, String?> {
    val obj = rawInput ?: return emptyMap()
    return obj.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull }
  }

  companion object {
    /**
     * Kinds we act on. The agent may send anything it likes; hooks, the audit log and the fuses
     * decide by this value, so it is pinned to a closed list instead of being trusted verbatim.
     */
    val KNOWN_KINDS = setOf("read", "edit", "delete", "move", "search", "execute", "think", "fetch", "other")

    /** Longest title we keep: it goes into balloons, the audit log and hook payloads. */
    const val MAX_TITLE_CHARS = 200

    /** Unknown kind → `other`; the original is not lost, it stays in [ToolCall.toolName]/rawInput. */
    fun normalizeKind(raw: String?): String? {
      val value = raw?.trim()?.lowercase() ?: return null
      return if (value in KNOWN_KINDS) value else "other"
    }

    /**
     * A title is a label, not a document: one line, no invisible or bidi characters, capped.
     * Without this an agent could push newlines into the audit log (forging a record) or hide
     * text inside a confirmation the user is about to approve.
     */
    fun normalizeTitle(raw: String?): String? {
      val value = raw ?: return null
      val flat = com.vibe.agent.security.ContextSanitizer.sanitize(value).text
        .replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
        .replace(Regex(" {2,}"), " ")
        .trim()
      if (flat.isEmpty()) return null
      return if (flat.length <= MAX_TITLE_CHARS) flat else flat.take(MAX_TITLE_CHARS - 1) + "…"
    }

    const val STATUS_PENDING = "pending"
    const val STATUS_IN_PROGRESS = "in_progress"
    const val STATUS_COMPLETED = "completed"
    const val STATUS_FAILED = "failed"
  }
}

/**
 * Thread-safe registry of the running turn's tool-calls. The reader thread
 * mutates it as frames arrive; the EDT reads snapshots for rendering. Cleared at
 * the start of every turn — tool-call ids are unique only within a turn.
 */
class ToolCallRegistry {
  private val byId = ConcurrentHashMap<String, ToolCall>()

  fun reset() = byId.clear()

  operator fun get(id: String): ToolCall? = byId[id]

  fun snapshot(): List<ToolCall> = byId.values.toList()

  /** Upsert from a `tool_call` frame; returns the (new or updated) entry. */
  fun onToolCall(update: JsonObject): ToolCall? {
    val id = update["toolCallId"]?.jsonPrimitive?.contentOrNull ?: return null
    // Everything the agent sends is data, not fact: titles and kinds are normalised before they
    // reach the audit log, the hooks and the fuses.
    val title = ToolCall.normalizeTitle(update["title"]?.jsonPrimitive?.contentOrNull)
    val kind = ToolCall.normalizeKind(update["kind"]?.jsonPrimitive?.contentOrNull)
    val status = update["status"]?.jsonPrimitive?.contentOrNull ?: ToolCall.STATUS_PENDING
    val name = ToolCall.normalizeTitle(update["name"]?.jsonPrimitive?.contentOrNull)
    val rawInput = update["rawInput"] as? JsonObject
    val existing = byId[id]
    val call = existing?.also {
      title?.let { t -> it.title = t }
      kind?.let { k -> it.kind = k }
      it.status = status
      name?.let { n -> it.toolName = n }
      rawInput?.let { r -> it.rawInput = r }
    } ?: ToolCall(
      id = id,
      title = title ?: name ?: kind ?: "инструмент",
      kind = kind,
      status = status,
      toolName = name,
      rawInput = rawInput,
    ).also { byId[id] = it }
    call.terminalId = call.terminalId ?: terminalIdOf(update)
    locationsOf(update)?.let { call.locations = it }
    return call
  }

  /** Apply a `tool_call_update` frame; returns the affected entry (may be created lazily). */
  fun onToolCallUpdate(update: JsonObject): ToolCall? {
    val id = update["toolCallId"]?.jsonPrimitive?.contentOrNull ?: return null
    val call = byId.getOrPut(id) {
      ToolCall(
        id = id,
        title = ToolCall.normalizeTitle(update["title"]?.jsonPrimitive?.contentOrNull) ?: "инструмент",
        kind = ToolCall.normalizeKind(update["kind"]?.jsonPrimitive?.contentOrNull),
        status = ToolCall.STATUS_IN_PROGRESS,
        toolName = update["name"]?.jsonPrimitive?.contentOrNull,
        rawInput = update["rawInput"] as? JsonObject,
      )
    }
    update["status"]?.jsonPrimitive?.contentOrNull?.let { call.status = it }
    update["title"]?.jsonPrimitive?.contentOrNull?.let { call.title = it }
    (update["rawInput"] as? JsonObject)?.let { call.rawInput = it }
    (terminalIdOf(update))?.let { call.terminalId = it }
    locationsOf(update)?.let { call.locations = it }
    return call
  }

  /** Parse the ACP `locations` array (`[{path, line?}]`) into a list of paths. */
  private fun locationsOf(update: JsonObject): List<String>? {
    val arr = update["locations"] as? kotlinx.serialization.json.JsonArray ?: return null
    val paths = arr.mapNotNull { (it as? JsonObject)?.get("path")?.jsonPrimitive?.contentOrNull }
    return paths.ifEmpty { null }
  }

  private fun terminalIdOf(update: JsonObject): String? {
    val meta = update["_meta"] as? JsonObject ?: return null
    (meta["terminal_info"] as? JsonObject)?.get("terminal_id")?.jsonPrimitive?.contentOrNull?.let { return it }
    (meta["terminal_output"] as? JsonObject)?.get("terminal_id")?.jsonPrimitive?.contentOrNull?.let { return it }
    (meta["terminal_exit"] as? JsonObject)?.get("terminal_id")?.jsonPrimitive?.contentOrNull?.let { return it }
    return null
  }
}
