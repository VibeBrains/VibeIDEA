// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * «What did the agent read, write and run» — per agent, over the lines of the journal.
 *
 * The panel shows the journal as it is written: one line per event, which answers «what happened
 * at 14:02» and never «what did this agent touch». After an agent has been reading personal or
 * private files, the second question is the one a person asks, and answering it by scrolling is not
 * an answer.
 *
 * Only what the journal already holds: paths the privacy filter let through, never contents.
 * Pure: journal lines in, a summary out.
 */
object AuditReadSummary {
  data class Agent(
    val name: String,
    val read: Set<String>,
    val written: Set<String>,
    val commands: Int,
  )

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  /** [sessionId] narrows to one conversation; null summarises every line given. */
  fun of(lines: List<String>, sessionId: String? = null): List<Agent> {
    val read = LinkedHashMap<String, MutableSet<String>>()
    val written = LinkedHashMap<String, MutableSet<String>>()
    val commands = LinkedHashMap<String, Int>()
    for (line in lines) {
      val obj = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
      if (sessionId != null && obj.str("sessionId") != sessionId) continue
      val actor = obj["actor"] as? JsonObject ?: continue
      if (actor.str("kind") != "agent") continue
      val name = actor.str("agent") ?: actor.str("role") ?: UNKNOWN
      val files = (obj["files"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
      when (obj.str("action")) {
        AuditEvent.Action.FS_WRITE -> written.getOrPut(name) { LinkedHashSet() }.addAll(files)
        AuditEvent.Action.TERMINAL -> commands[name] = (commands[name] ?: 0) + 1
        AuditEvent.Action.TOOL_CALL_DONE -> {
          val tool = (obj["meta"] as? JsonObject)?.str("tool").orEmpty()
          when {
            ToolCallAudit.isCommandTool(tool) -> commands[name] = (commands[name] ?: 0) + 1
            isWriteTool(tool) -> written.getOrPut(name) { LinkedHashSet() }.addAll(files)
            else -> read.getOrPut(name) { LinkedHashSet() }.addAll(files)
          }
        }
      }
    }
    val names = LinkedHashSet<String>().apply { addAll(read.keys); addAll(written.keys); addAll(commands.keys) }
    return names.map { Agent(it, read[it].orEmpty(), written[it].orEmpty(), commands[it] ?: 0) }
  }

  /** Tools whose target is a file they change; everything else with a path counts as a read. */
  private fun isWriteTool(tool: String): Boolean {
    val t = tool.lowercase()
    return WRITE_HINTS.any { it in t }
  }

  private val WRITE_HINTS = listOf("write", "edit", "create", "replace", "delete", "move", "rename", "patch")
  const val UNKNOWN = "?"

  private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
  private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
