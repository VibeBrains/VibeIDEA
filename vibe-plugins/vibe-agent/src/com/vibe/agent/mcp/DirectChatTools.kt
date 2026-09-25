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
class DirectChatTools(
  private val sources: List<Source>,
  /** Потолок одного вызова; свой в тестах, чтобы не ждать полминуты ради проверки самого потолка. */
  private val callTimeoutMs: Long = CALL_TIMEOUT_MS,
  /** Who answers a server that asks for input in the middle of a call ([McpInputRequired]); the person, in the panel */
  private val answerer: McpInputRequired.Answerer = McpInputRequired.Answerer.NONE,
) : AutoCloseable {
  /** One place tools come from. */
  interface Source : AutoCloseable {
    /** The tools this source offers now. Throws when it should offer tools and cannot. */
    fun specs(): List<ToolSpec>

    fun riskOf(tool: String): McpProtocol.Risk

    /** [answer] — who answers the server when it asks for input before the call completes */
    fun call(tool: String, arguments: JsonObject,
             answer: McpInputRequired.Answerer = McpInputRequired.Answerer.NONE): McpClient.CallResult

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
      // Потолок на КАЖДЫЙ вызов, не только на сервер памяти: инструмент IDE, ушедший в долгую работу,
      // останавливал ход молча — человек видел значок вызова и больше ничего (18.09.2026).
      val human = HumanTime()
      val answer = McpInputRequired.Answerer { request -> human.during { answerer.answer(request) } }
      val result = withCeiling(call, ceilingFor(call.name), human) { source.call(call.name, call.argumentsObject(), answer) }
      ToolResult(call.id, call.name, result.text, result.isError)
    }
    catch (e: java.util.concurrent.TimeoutException) {
      ToolResult(call.id, call.name,
                 t("directTools.timedOut", "tool" to call.name, "seconds" to ceilingFor(call.name) / 1000), isError = true)
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

  /**
   * Runs one tool call with a ceiling, on a thread of its own.
   *
   * The work is not killed — a tool half-way through a write must not be torn in two — but the TURN is freed: the model
   * gets «инструмент не ответил за N с» and can say so or try something else. A turn that waits forever looks broken
   * and cannot even be stopped by the person, because nothing is streaming.
   */
  /**
   * Потолок КОНКРЕТНОГО инструмента, а не один на всех.
   *
   * Полминуты хватает поиску по индексу и чтению файла, но не хватает ничему из того, ради чего
   * агенту дали команды: `npm test`, сборка и `git clone` живут минутами. С общим потолком агент
   * не мог прогнать собственную проверку — то есть руки ему дали, а работу ими сделать нельзя
   * (найдено перечиткой 18.09.2026, в тот же день, что руки и появились).
   */
  private fun ceilingFor(tool: String): Long =
    if (tool == McpProtocol.TOOL_RUN_COMMAND) COMMAND_TIMEOUT_MS else callTimeoutMs

  private fun <T> withCeiling(call: ToolCall, ceilingMs: Long, human: HumanTime, body: () -> T): T {
    val task = java.util.concurrent.FutureTask(body)
    Thread(task, "vibe-direct-tool-" + call.name).apply { isDaemon = true }.start()
    val started = System.currentTimeMillis()
    while (true) {
      val left = ceilingMs - (System.currentTimeMillis() - started - human.spentMs())
      val wait = if (human.waiting()) HUMAN_POLL_MS else left.coerceAtLeast(1)
      try {
        return task.get(wait, java.util.concurrent.TimeUnit.MILLISECONDS)
      }
      catch (e: java.util.concurrent.ExecutionException) {
        throw (e.cause ?: e)
      }
      catch (e: java.util.concurrent.TimeoutException) {
        // A person filling in the server's form is not the tool keeping silent: their time is not the tool's
        if (!human.waiting() && System.currentTimeMillis() - started - human.spentMs() >= ceilingMs) throw e
      }
    }
  }

  /** The time a call spent waiting for the person, which the ceiling does not count */
  private class HumanTime {
    private val active = java.util.concurrent.atomic.AtomicInteger()
    private val spent = java.util.concurrent.atomic.AtomicLong()
    private val since = java.util.concurrent.atomic.AtomicLong()

    fun waiting(): Boolean = active.get() > 0

    fun spentMs(): Long = spent.get() + if (waiting()) System.currentTimeMillis() - since.get() else 0

    fun <T> during(body: () -> T): T {
      if (active.getAndIncrement() == 0) since.set(System.currentTimeMillis())
      try {
        return body()
      }
      finally {
        if (active.decrementAndGet() == 0) spent.addAndGet(System.currentTimeMillis() - since.get())
      }
    }
  }

  companion object {
    /** A search over the history corpus may read many files; half a minute before it counts as silence. */
    const val CALL_TIMEOUT_MS = 30_000L

    /**
     * Команда оболочки — другой разговор: тесты и сборка идут минутами, и полминуты означали бы
     * «агенту нельзя прогонять проверки». Десять минут — больше любой разумной проверки и меньше
     * бесконечности; сама команда останавливается раньше своим потолком и говорит об этом.
     */
    const val COMMAND_TIMEOUT_MS = 600_000L

    /** How often a call waiting on the person looks again at its ceiling */
    private const val HUMAN_POLL_MS = 500L
  }
}

/**
 * The shared VibeMemory store over [McpClient].
 *
 * One server per chat panel, started on the first turn that offers tools and kept for the next ones — the
 * store is read on every search, and a process per turn would be a cost nobody sees. A server that died is
 * started again on the next turn. Not installed — no tools, and no error.
 *
 * A tool this class does not know is treated as writing: a new tool must not get in unasked because nobody
 * updated [READ_TOOLS].
 */
class MemoryServerSource(
  private val connect: () -> McpClient?,
  private val clientVersion: String,
  private val timeoutMs: Long = DirectChatTools.CALL_TIMEOUT_MS,
) : DirectChatTools.Source {
  private var client: McpClient? = null
  private var tools: List<McpClient.RemoteTool> = emptyList()

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

  override fun call(tool: String, arguments: JsonObject, answer: McpInputRequired.Answerer): McpClient.CallResult {
    val current = synchronized(this) { client?.takeIf { it.isAlive } } ?: throw McpClient.McpException("server stopped")
    return current.callTool(tool, arguments, timeoutMs, answer)
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

  override fun call(tool: String, arguments: JsonObject, answer: McpInputRequired.Answerer): McpClient.CallResult {
    if (tool in EXCLUDED) throw McpClient.McpException("not offered in the direct chat")
    val result = dispatch(tool, arguments)
    return McpClient.CallResult(result.text, result.isError)
  }

  companion object {
    val EXCLUDED: Set<String> = setOf(McpProtocol.TOOL_RUN)
  }
}
