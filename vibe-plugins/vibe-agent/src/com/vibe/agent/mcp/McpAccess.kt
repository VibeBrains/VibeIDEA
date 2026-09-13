// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.i18n.VibeI18n.t

/**
 * What MCP tools may do on this machine.
 *
 * Two independent questions, asked in this order:
 * 1. Is the project trusted? Trust answers «may foreign code run here». Our hooks always asked it and
 *    refused in an untrusted project, while `/mcp` did not ask at all — an outside client started the
 *    agent and wrote files. Untrusted means reading only, whatever the settings say.
 * 2. Did the human open this class of tool? Turning on the HTTP API for the import graph must not
 *    silently hand out `vibe_run_agent` as well, so writing and executing are separate switches, both
 *    off by default.
 *
 * Reading is never gated: it lets nothing in the project execute.
 */
object McpAccess {
  enum class Verdict { ALLOWED, UNTRUSTED, DISABLED }

  fun verdict(risk: McpProtocol.Risk, trusted: Boolean, allowWrite: Boolean, allowExecute: Boolean): Verdict = when {
    risk == McpProtocol.Risk.READ -> Verdict.ALLOWED
    // Trust first: an untrusted project stays read-only even with both switches on.
    !trusted -> Verdict.UNTRUSTED
    risk == McpProtocol.Risk.WRITE -> if (allowWrite) Verdict.ALLOWED else Verdict.DISABLED
    else -> if (allowExecute) Verdict.ALLOWED else Verdict.DISABLED
  }

  /**
   * The refusal names the reason and the way to lift it.
   *
   * A bare refusal reads to the agent as a broken tool and it retries; a named reason turns it into a
   * human decision the agent can pass on in words. The two reasons are lifted in different places, so
   * they are two texts.
   */
  fun refusal(verdict: Verdict): String? = when (verdict) {
    Verdict.ALLOWED -> null
    Verdict.UNTRUSTED -> t("mcp.refusal.untrusted")
    Verdict.DISABLED -> t("mcp.refusal.disabled")
  }
}
