// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.acp

import com.vibe.agent.mcp.McpAccess
import com.vibe.agent.mcp.McpProtocol.Risk

/**
 * Who decides when an ACP agent asks `session/request_permission` or writes through `fs/write_text_file`.
 *
 * The agent owns the decision of WHEN to ask (its own session mode); the IDE owns exactly one thing here: the
 * workspace trust. The IDE permission mode (autopilot / edits / manual / plan) belongs to the direct chat and has no
 * parameter in this policy on purpose: two owners of one decision would auto-answer a question the agent's mode
 * chose to put to the person.
 */
object AcpPermissionPolicy {
  enum class Answer { ASK_USER, REFUSE_UNTRUSTED }

  /** The risk class of an ACP tool kind; a kind the IDE does not know is the worst case, not the harmless one */
  fun riskOf(kind: String?): Risk = when (kind) {
    "read", "search", "fetch", "think", "switch_mode" -> Risk.READ
    "edit", "delete", "move" -> Risk.WRITE
    else -> Risk.EXECUTE
  }

  /** An untrusted project stays read-only; anything else goes to the person, whatever the IDE mode says */
  fun answer(kind: String?, projectTrusted: Boolean): Answer =
    if (McpAccess.verdict(riskOf(kind), projectTrusted, allowWrite = true, allowExecute = true) == McpAccess.Verdict.UNTRUSTED)
      Answer.REFUSE_UNTRUSTED
    else Answer.ASK_USER
}
