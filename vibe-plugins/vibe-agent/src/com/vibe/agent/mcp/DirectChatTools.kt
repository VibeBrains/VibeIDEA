// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.providers.ToolCall
import com.vibe.agent.providers.ToolResult
import com.vibe.agent.providers.ToolSpec
import kotlinx.serialization.json.JsonObject

/**
 * The tools of the direct chat, gathered from their sources: the IDE's own tools and the shared VibeMemory store.
 *
 * Rights are the classes the IDE already has ([McpProtocol.Risk]): reading runs, writing and executing are the
 * caller's decision — the direct chat asks the person for each such call. A name offered by two sources goes
 * to the first: the list order is the precedence.
 *
 * Free of IDE types: how to start a server, how to run an IDE tool and how to ask the person are handed in.
 */
class DirectChatTools(private val sources: List<Source>) : AutoCloseable {
  /** One place tools come from. */
  interface Source : AutoCloseable {
    /** The tools this source offers now. Throws when it should offer tools and cannot. */
    fun specs(): List<ToolSpec>

    fun riskOf(tool: String): McpProtocol.Risk

    fun call(tool: String, arguments: JsonObject): McpStdioClient.CallResult

    override fun close() {}
  }

  private var owners: Map<String, Source> = emptyMap()

  /** The tools to offer on this turn. A failing source is reported through [onFailure] and skipped. */
  @Synchronized
  fun specs(onFailure: (Exception) -> Unit): List<ToolSpec> {
    val owned = LinkedHashMap<String, Source>()
    val offered = ArrayList<ToolSpec>()
    for (source in sources) {
      val specs = try {
        source.specs()
      }
      catch (e: Exception) {
        onFailure(e)
        emptyList()
      }
      specs.filter { it.name !in owned }.forEach {
        owned[it.name] = source
        offered += it
      }
    }
    owners = owned
    return offered
  }

  /**
   * Runs one call. Never throws: every failure becomes a result the model reads, so the conversation goes
   * on and the model can tell the person what did not work.
   *
   * [approve] decides whether the call may run, given its risk; a refusal is reported to the model as such.
   * A tool no source offered on this turn is not asked about at all.
   */
  fun execute(call: ToolCall, approve: (ToolCall, McpProtocol.Risk) -> Boolean): ToolResult {
    val source = synchronized(this) { owners[call.name] }
      ?: return ToolResult(call.id, call.name, t("directTools.unknown", "tool" to call.name), isError = true)
    if (!approve(call, source.riskOf(call.name))) {
      return ToolResult(call.id, call.name, t("directTools.refused", "tool" to call.name), isError = true)
    }
    return try {
      val result = source.call(call.name, call.argumentsObject())
      ToolResult(call.id, call.name, result.text, result.isError)
    }
    catch (e: Exception) {
      ToolResult(call.id, call.name, t("directTools.failed", "tool" to call.name, "reason" to (e.message ?: "")), isError = true)
    }
  }

  @Synchronized
  override fun close() {
    sources.forEach { runCatching { it.close() } }
    owners = emptyMap()
  }

  companion object {
    /** A search over the history corpus may read many files; half a minute before it counts as silence. */
    const val CALL_TIMEOUT_MS = 30_000L
  }
}

/**
 * The shared VibeMemory store over [McpStdioClient].
 *
 * One server per chat panel, started on the first turn that offers tools and kept for the next ones — the
 * store is read on every search, and a process per turn would be a cost nobody sees. A server that died is
 * started again on the next turn. Not installed — no tools, and no error.
 *
 * A tool this class does not know is treated as writing: a new tool must not get in unasked because nobody
 * updated [READ_TOOLS].
 */
class MemoryServerSource(
  private val connect: () -> McpStdioClient?,
  private val clientVersion: String,
  private val timeoutMs: Long = DirectChatTools.CALL_TIMEOUT_MS,
) : DirectChatTools.Source {
  private var client: McpStdioClient? = null
  private var tools: List<McpStdioClient.RemoteTool> = emptyList()

  @Synchronized
  override fun specs(): List<ToolSpec> {
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

  override fun riskOf(tool: String): McpProtocol.Risk = Companion.riskOf(tool)

  override fun call(tool: String, arguments: JsonObject): McpStdioClient.CallResult {
    val current = synchronized(this) { client?.takeIf { it.isAlive } } ?: throw McpStdioClient.McpException("server stopped")
    return current.callTool(tool, arguments, timeoutMs)
  }

  @Synchronized
  override fun close() {
    client?.close()
    client = null
    tools = emptyList()
  }

  companion object {
    /** VibeMemory's reading tools; everything else writes. */
    val READ_TOOLS: Set<String> = setOf("memory_search", "memory_get", "history_search", "project_resolve")

    fun riskOf(tool: String): McpProtocol.Risk =
      if (tool in READ_TOOLS) McpProtocol.Risk.READ else McpProtocol.Risk.WRITE
  }
}

/**
 * The IDE's own tools — the catalogue the MCP server offers outside clients ([McpProtocol.TOOLS]).
 *
 * `vibe_run_agent` is left out: it hands a task to the agent of the same panel whose turn is running, and a
 * chat that calls it waits on itself.
 */
class IdeToolsSource(private val dispatch: (String, JsonObject) -> McpServer.Tools.Result) : DirectChatTools.Source {
  override fun specs(): List<ToolSpec> =
    McpProtocol.TOOLS.filter { it.name !in EXCLUDED }.map { ToolSpec(it.name, it.description, it.schema) }

  override fun riskOf(tool: String): McpProtocol.Risk = McpProtocol.riskOf(tool)

  override fun call(tool: String, arguments: JsonObject): McpStdioClient.CallResult {
    if (tool in EXCLUDED) throw McpStdioClient.McpException("not offered in the direct chat")
    val result = dispatch(tool, arguments)
    return McpStdioClient.CallResult(result.text, result.isError)
  }

  companion object {
    val EXCLUDED: Set<String> = setOf(McpProtocol.TOOL_RUN)
  }
}
