// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.providers.ToolCall
import com.vibe.agent.providers.ToolResult
import com.vibe.agent.providers.ToolSpec

/**
 * The tools of the direct chat: the shared VibeMemory store, spoken to through [McpStdioClient].
 *
 * One server per chat panel, started on the first turn that offers tools and kept for the next ones —
 * the store is read on every search, and a process per turn would be a cost nobody sees. A server that
 * died is started again on the next turn.
 *
 * Rights follow the classes the IDE already has ([McpAccess]): searching and reading is allowed, saving,
 * updating and deleting a record is writing — the caller asks the person first. A tool the server lists
 * but this class does not know is treated as writing: a new tool must not get in unasked because nobody
 * updated a list.
 *
 * Free of IDE types: how to start the server and how to ask the person are handed in.
 */
class DirectChatTools(
  private val connect: () -> McpStdioClient?,
  private val clientVersion: String,
  private val timeoutMs: Long = CALL_TIMEOUT_MS,
) : AutoCloseable {
  private var client: McpStdioClient? = null
  private var tools: List<McpStdioClient.RemoteTool> = emptyList()

  /**
   * The tools to offer on this turn; empty when the server is not installed.
   * Throws when the server is installed but did not answer — the caller says so once.
   */
  @Synchronized
  fun specs(): List<ToolSpec> {
    if (client?.isAlive != true) {
      client?.close()
      tools = emptyList()
      val fresh = connect() ?: run { client = null; return emptyList() }
      try {
        fresh.initialize(clientVersion, timeoutMs)
        tools = fresh.listTools(timeoutMs)
        client = fresh
      }
      catch (e: Exception) {
        fresh.close()
        client = null
        throw e
      }
    }
    return tools.map { ToolSpec(it.name, it.description, it.inputSchema) }
  }

  /**
   * Runs one call. Never throws: every failure becomes a result the model reads, so the conversation goes
   * on and the model can tell the person what did not work.
   *
   * [approve] decides whether the call may run, given its risk; a refusal is reported to the model as such.
   */
  fun execute(call: ToolCall, approve: (ToolCall, McpProtocol.Risk) -> Boolean): ToolResult {
    val current = synchronized(this) { client?.takeIf { it.isAlive && tools.any { tool -> tool.name == call.name } } }
      ?: return ToolResult(call.id, call.name, t("directTools.unknown", "tool" to call.name), isError = true)
    if (!approve(call, riskOf(call.name))) {
      return ToolResult(call.id, call.name, t("directTools.refused", "tool" to call.name), isError = true)
    }
    return try {
      val result = current.callTool(call.name, call.argumentsObject(), timeoutMs)
      ToolResult(call.id, call.name, result.text, result.isError)
    }
    catch (e: Exception) {
      ToolResult(call.id, call.name, t("directTools.failed", "tool" to call.name, "reason" to (e.message ?: "")), isError = true)
    }
  }

  @Synchronized
  override fun close() {
    client?.close()
    client = null
    tools = emptyList()
  }

  companion object {
    /** A search over the history corpus may read many files; half a minute before it counts as silence. */
    const val CALL_TIMEOUT_MS = 30_000L

    /** VibeMemory's reading tools; everything else writes (see the class comment). */
    val READ_TOOLS: Set<String> = setOf("memory_search", "memory_get", "history_search")

    fun riskOf(tool: String): McpProtocol.Risk =
      if (tool in READ_TOOLS) McpProtocol.Risk.READ else McpProtocol.Risk.WRITE
  }
}
