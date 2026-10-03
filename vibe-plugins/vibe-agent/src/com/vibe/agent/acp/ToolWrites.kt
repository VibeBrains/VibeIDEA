// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.acp

import com.vibe.agent.audit.ToolCallAudit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Which files an agent's tool call writes, as far as the call tells
 * Used where the turn learns what changed and where a step's write boundary is checked: one answer for both
 * Pure: the call's kind, name, input and locations in, the paths out
 */
object ToolWrites {
  /** Tool names known to write files — their declared path is a written file */
  val EDIT_TOOLS: Set<String> = setOf("write_text_file", "Write", "Edit", "MultiEdit", "NotebookEdit",
    "edit_file", "rewrite_file", "create_file_or_folder", "delete_file_or_folder")

  /** ACP kinds of a call that writes files */
  private val WRITE_KINDS = setOf("edit", "delete", "move")

  /**
   * The files a call writes: its path-shaped inputs and its locations, but only for a call that writes
   * ACP `locations` is read-inclusive, so harvesting it for a command (`grep KEY .env`) would name a read as a write
   */
  fun writtenPaths(kind: String?, name: String?, rawInput: JsonObject?, locations: List<String>): List<String> {
    val isWrite = (name != null && name in EDIT_TOOLS) || kind in WRITE_KINDS
    if (!isWrite) return emptyList()
    return (ToolCallAudit.PATH_KEYS.mapNotNull { rawInput?.get(it)?.jsonPrimitive?.contentOrNull } + locations).distinct()
  }
}
