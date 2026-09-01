// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One append-only audit record, VibeIDE `.vibe/audit.jsonl` contract:
 * a single `JSON.stringify(event)` per line. `ts` (unix millis) is the only
 * timestamp; the rest is deliberately narrow. Privacy is enforced by the
 * callers (see [com.vibe.agent.audit.ToolCallAudit]): tool arguments, command
 * bodies, search queries and file contents are NEVER written — only a tool
 * name and, for file tools, a truncated target path.
 */
data class AuditEvent(
  val ts: Long,
  val action: String,
  val ok: Boolean,
  /**
   * Who caused this. Deliberately WITHOUT a default: a default would be chosen once here and then
   * silently inherited by every future call site, which is exactly how the journal lost the answer
   * to «по чьей воле» in the first place. Making it a compile error is the point.
   */
  val actor: AuditActor,
  val files: List<String>? = null,
  val model: String? = null,
  val latencyMs: Long? = null,
  val meta: Map<String, String>? = null,
) {
  fun toJson(): JsonObject = buildJsonObject {
    put("ts", ts)
    put("action", action)
    put("ok", ok)
    put("actor", actor.toJson())
    files?.takeIf { it.isNotEmpty() }?.let { list ->
      put("files", kotlinx.serialization.json.JsonArray(list.map { JsonPrimitive(it) }))
    }
    model?.let { put("model", it) }
    latencyMs?.let { put("latencyMs", it) }
    meta?.takeIf { it.isNotEmpty() }?.let { m ->
      put("meta", buildJsonObject { m.forEach { (k, v) -> put(k, v) } })
    }
  }

  /**
   * Closed set of audit actions (VibeIDE union, trimmed to what the ACP client
   * can actually observe). New actions must be added here so the format stays a
   * documented contract, not an open bag of strings.
   */
  object Action {
    const val PROMPT = "prompt"
    const val REPLY = "reply"
    const val TOOL_CALL_START = "tool_call:start"
    const val TOOL_CALL_DONE = "tool_call:done"
    const val PERMISSION = "permission"
    const val FS_WRITE = "fs_write"
    const val HOOK = "hook"
    const val VERIFY_GATE = "verify_gate:result"
    const val TURN_CHECK = "turn_check:result"
    const val CIRCUIT_BREAKER_OPENED = "circuit_breaker_opened"
    const val CIRCUIT_BREAKER_RECOVERED = "circuit_breaker_recovered"
    const val CHECKPOINT = "checkpoint"
    const val TERMINAL = "terminal"

    /**
     * A secret was substituted into something that then ran.
     *
     * The NAME is recorded and never the value: when an incident is being unpicked, the first
     * question is «какие токены уехали вместе с этой командой», and today the log shows the command
     * and says nothing about what travelled with it. A log that answered by printing the token
     * would be the leak it was meant to help investigate.
     */
    const val SECRET_USED = "secret_used"
  }
}
