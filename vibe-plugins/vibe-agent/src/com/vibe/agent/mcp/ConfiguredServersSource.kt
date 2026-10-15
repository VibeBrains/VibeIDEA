// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.providers.ToolSpec
import kotlinx.serialization.json.JsonObject

/**
 * Инструменты СВОИХ серверов человека (`.vibe/mcp.json`) в прямом чате.
 *
 * Один источник на все серверы, а не по источнику на каждый: список серверов меняется правкой
 * файла, и держать соответствие «запись файла → объект в панели» значило бы синхронизировать две
 * коллекции ради ничего. Здесь же одно место, где видно, какой сервер не поднялся.
 *
 * Права: у ЧУЖОГО инструмента класс опасности неизвестен, и он считается ПИШУЩИМ — то есть
 * спрашивается у человека. Обратное умолчание («читает, пока не доказано иное») однажды тихо
 * выполнило бы чужое действие от его имени.
 *
 * Сервер, который не стартует, называется один раз и пропускается: чат, падающий из-за чужого
 * конфига, — это наш отказ обслуживать человека за его же опечатку.
 */
class ConfiguredServersSource(
  private val servers: () -> List<McpServersFile.Entry>,
  private val workingDir: java.nio.file.Path?,
  private val clientVersion: String,
  /**
   * Куда сказать о серверах, которые не поднялись, — по одному имени с причиной.
   *
   * Отдельным каналом, а не исключением: исключение забрало бы с собой инструменты всех остальных
   * серверов, и человек увидел бы «инструментов нет» вместо «один сервер не запустился».
   */
  private val onFailure: (List<String>) -> Unit = {},
  /**
   * Where to report that a server changed its tool descriptions after approval.
   *
   * A separate channel from start failures: this is not a breakage but a security event, and it reads differently —
   * "the server works, but it no longer promises what you agreed to".
   */
  private val onDrift: (String, ToolFingerprint.Drift) -> Unit = { _, _ -> },
  /** Called after every change of what [report] says; the settings page redraws the states from it */
  private val onReport: () -> Unit = {},
  private val timeoutMs: Long = DirectChatTools.CALL_TIMEOUT_MS,
) : DirectChatTools.Source {
  /**
   * What the source knows of its servers now: which ones run, which did not start and why
   *
   * Only this chat's servers are known here: an external agent starts its servers itself and tells the IDE nothing about them
   */
  class Report(val live: Map<String, Live>, val failed: Map<String, String>) {
    /** [tools] are the tools offered to the model; [withheld]: the server answered, but its tools wait for the person */
    class Live(val tools: Int, val withheld: Boolean, val alive: Boolean)

    companion object {
      val EMPTY = Report(emptyMap(), emptyMap())
    }
  }

  private class Running(
    val entry: McpServersFile.Entry,
    val client: McpClient,
    val tools: List<String>,
    /** Схемы запоминаются вместе с именами: список инструментов сервера меняется его перезапуском. */
    val specs: List<ToolSpec>,
    /** The set the server returned while it is held back for the person's confirmation; null when nothing is held */
    val held: List<ToolSpec>? = null,
  )

  private val running = LinkedHashMap<String, Running>()

  /** Why a server did not start, in words, until the next attempt */
  private val failed = LinkedHashMap<String, String>()

  /** Replaced whole after every change, so that a reader on the UI thread never waits for a server that is starting */
  @Volatile private var published = Report.EMPTY

  fun report(): Report = published

  @Synchronized
  override fun specs(): List<ToolSpec> {
    val wanted = servers().filterNot { it.disabled }
    // Сервер, исчезнувший из файла или выключенный, гасится: живой процесс, о котором никто не
    // просил, — это утечка, которую видно только в диспетчере задач.
    running.keys.toList().filter { name -> wanted.none { it.name == name } }.forEach { stop(it) }
    failed.keys.retainAll(wanted.mapTo(HashSet()) { it.name })
    val specs = ArrayList<ToolSpec>()
    val failures = ArrayList<String>()
    for (entry in wanted) {
      // Список инструментов живого сервера НЕ перезапрашивается: он меняется только вместе с
      // самим сервером, а лишний круг по stdio — это задержка в начале каждого хода.
      specs += live(entry)?.specs ?: start(entry, failures)
    }
    // Жалоба НЕ отменяет остальных: источник, бросивший исключение, отдаёт панели пустой список —
    // то есть один сервер с опечаткой в команде забирал с собой инструменты всех соседей. Поэтому
    // о неудачах докладываем отдельно, а собранное отдаём (перечитка 18.09.2026).
    if (failures.isNotEmpty()) onFailure(failures)
    publish()
    return specs
  }

  /**
   * Stops one server and starts it again now, with the entry as the file has it: one that is off or gone stays stopped
   *
   * Blocks until the server answers or fails, so not for the UI thread. Other servers are left as they are
   * A failure is not sent to [onFailure]: the person asked for this start and reads the answer where they asked
   */
  @Synchronized
  fun restart(name: String) {
    stop(name)
    failed.remove(name)
    servers().firstOrNull { it.name == name && !it.disabled }?.let { start(it, ArrayList()) }
    publish()
  }

  /**
   * Looks again at the tools held back for [name], without starting anything
   *
   * The person confirms a changed set in a dialog that knows nothing of this source: without a second look the server would
   * stay held until its process ends
   */
  @Synchronized
  fun reconsider(name: String) {
    servers().firstOrNull { it.name == name && !it.disabled }?.let { live(it) }
    publish()
  }

  /** The running server of this entry, or null when it must be started: never started, gone, or edited in the file since */
  private fun live(entry: McpServersFile.Entry): Running? {
    val current = running[entry.name]?.takeIf { it.client.isAlive && it.entry == entry } ?: return null
    val held = current.held ?: return current
    if (!ApprovedTools.drift(workingDir?.toString(), entry.name, held).isEmpty) return current
    // What the server returns now is what the person confirmed: the held tools reach the model
    return Running(entry, current.client, held.map { it.name }, held).also { running[entry.name] = it }
  }

  /** Starts the server and returns the tools to offer; a failure is recorded under the name and told through [failures] */
  private fun start(entry: McpServersFile.Entry, failures: MutableList<String>): List<ToolSpec> {
    stop(entry.name)
    failed.remove(entry.name)
    // A process that started but never answered must not outlive the attempt: a restart from the page repeats it at will
    var started: McpClient? = null
    return runCatching {
      val client = McpClient.start(entry.command, entry.args, workingDir, entry.env).also { started = it }
      client.initialize(clientVersion, timeoutMs)
      val tools = client.listTools(timeoutMs)
      val specs = tools.map { ToolSpec(it.name, it.description, it.inputSchema) }
      // Compare with what the person agreed to. A set that changed after approval is NOT given to the model:
      // the consent covered other descriptions, and descriptions are what the person decides by.
      val project = workingDir?.toString()
      val drift = ApprovedTools.drift(project, entry.name, specs)
      when {
        ApprovedTools.isFirstSight(project, entry.name) -> {
          // First sight: nothing to approve yet, so the set becomes the baseline. Permissions do not change —
          // every call of a foreign tool is still asked about ([riskOf]).
          ApprovedTools.approve(project, entry.name, specs)
          running[entry.name] = Running(entry, client, tools.map { it.name }, specs)
          specs
        }
        drift.isEmpty -> {
          running[entry.name] = Running(entry, client, tools.map { it.name }, specs)
          specs
        }
        else -> {
          onDrift(entry.name, drift)
          // The server keeps running (it may still be confirmed), but it contributes no tools.
          running[entry.name] = Running(entry, client, emptyList(), emptyList(), held = specs)
          emptyList()
        }
      }
    }.getOrElse { error ->
      started?.let { runCatching { it.close() } }
      val reason = McpServerFailure.reasonOf(error)
      failed[entry.name] = reason
      failures += entry.name + ": " + reason
      emptyList()
    }
  }

  private fun publish() {
    published = Report(
      running.mapValues { (_, r) -> Report.Live(r.held?.size ?: r.tools.size, r.held != null, r.client.isAlive) },
      failed.toMap(),
    )
    onReport()
  }

  override fun riskOf(tool: String): McpProtocol.Risk = McpProtocol.Risk.WRITE

  override fun call(tool: String, arguments: JsonObject, answer: McpInputRequired.Answerer): McpClient.CallResult {
    val owner = synchronized(this) { running.values.firstOrNull { tool in it.tools && it.client.isAlive } }
      ?: throw McpClient.McpException("сервер этого инструмента не запущен: $tool")
    return owner.client.callTool(tool, arguments, timeoutMs, answer)
  }

  @Synchronized
  override fun close() {
    running.keys.toList().forEach { stop(it) }
  }

  private fun stop(name: String) {
    running.remove(name)?.let { runCatching { it.client.close() } }
  }
}
