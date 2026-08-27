// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

/**
 * Privacy filter for tool-call audit, VibeIDE `toolCallAudit.ts` verbatim:
 * arguments, command bodies, search queries and file contents are NEVER
 * recorded. The single exception is the target path of file tools, truncated
 * to [MAX_TARGET_LEN]. Command tools do not even get a path — their argument is
 * the sensitive part.
 */
object ToolCallAudit {
  const val MAX_TARGET_LEN = 260

  /** Tools whose whole payload is the command line — no path is safe to record. */
  private val COMMAND_TOOLS = setOf(
    "run_command", "run_persistent_command", "kill_background_command",
    "read_background_output", "run_nl_command", "open_persistent_terminal",
    "kill_persistent_terminal",
  )

  /**
   * The one path (if any) safe to store for [tool], derived from raw params.
   * Returns null for command tools and when no path-shaped field is present.
   */
  fun safeTargetPath(tool: String, rawParams: Map<String, String?>): String? {
    if (tool in COMMAND_TOOLS) return null
    val raw = rawParams["uri"] ?: rawParams["path"] ?: rawParams["dirUri"] ?: return null
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return if (trimmed.length > MAX_TARGET_LEN) trimmed.take(MAX_TARGET_LEN) else trimmed
  }

  fun isCommandTool(tool: String): Boolean = tool in COMMAND_TOOLS
}
