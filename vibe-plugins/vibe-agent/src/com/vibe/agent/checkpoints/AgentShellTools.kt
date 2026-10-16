// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.checkpoints

import com.vibe.agent.audit.ToolCallAudit

/**
 * Which name an ACP agent's tool row is recorded under, for the rewind warning
 *
 * The contract counts commands by the direct chat's tool names ([CheckpointContract.SHELL_TOOL_NAMES]) and compares them
 * exactly, while an ACP agent calls its command tool whatever it likes: `Bash`, `run_shell_command`, `shell`
 * What the agents have in common is the ACP tool kind `execute`, so a call of that kind (or a tool the audit already
 * knows as a command tool) is recorded as `run_command`, and every other tool keeps its own name
 * The two tools that only follow a command (read its output, kill it) are not commands of their own and stay as they are
 */
object AgentShellTools {
  /** The name a command of an ACP agent is recorded under: the direct chat's command tool */
  const val COMMAND = "run_command"

  private val FOLLOW_UPS = setOf("BashOutput", "KillShell")

  fun recordedName(kind: String?, toolName: String?): String? = when {
    toolName in FOLLOW_UPS -> toolName
    ToolCallAudit.isCommandTool(toolName.orEmpty(), kind) -> COMMAND
    else -> toolName
  }
}
