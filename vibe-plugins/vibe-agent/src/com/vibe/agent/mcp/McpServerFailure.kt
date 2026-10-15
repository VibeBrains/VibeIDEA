// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.acp.AgentFailure
import com.vibe.agent.acp.AgentLaunchException
import com.vibe.agent.i18n.VibeI18n.t

/**
 * Why a server of `.vibe/mcp.json` did not start, in words a person reads in the feed and on the settings page
 *
 * The exceptions of the client carry English diagnostics meant for the log; here they become a cause and a next step.
 * A program that cannot be found is the usual one and gets its own wording (it is a server, not an agent as in [AgentFailure])
 */
object McpServerFailure {
  private const val MS_PER_SECOND = 1000L

  fun reasonOf(error: Throwable): String {
    val root = AgentFailure.rootOf(error)
    return when {
      root is AgentLaunchException -> launchReason(root)
      root is McpClient.NoAnswer -> t("mcp.servers.reason.noAnswer", "seconds" to root.timeoutMs / MS_PER_SECOND)
      // A write to the stdin of a process that has just died fails with an IOException of its own
      root is McpClient.Stopped || root is java.io.IOException -> t("mcp.servers.reason.stopped")
      else -> root.message?.takeIf { it.isNotBlank() } ?: root.javaClass.simpleName
    }
  }

  /** A server that started, answered and went away later: nothing was thrown, so the cause is only known as a fact */
  fun exited(): String = t("mcp.servers.reason.exited")

  private fun launchReason(error: AgentLaunchException): String = when (error.kind) {
    AgentLaunchException.Kind.NOT_FOUND ->
      if (error.nodeTool) t("mcp.servers.reason.nodeMissing", "command" to error.command)
      else t("mcp.servers.reason.notFound", "command" to error.command)
    AgentLaunchException.Kind.START_FAILED -> t("mcp.servers.reason.startFailed", "command" to error.command, "reason" to error.detail)
  }
}
