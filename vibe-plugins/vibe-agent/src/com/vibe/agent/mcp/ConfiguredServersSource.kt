// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
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
   * Куда сказать, что сервер изменил описания инструментов после одобрения.
   *
   * Отдельным каналом от неудач запуска: это не поломка, а событие безопасности, и читается оно
   * иначе — «сервер работает, но обещает уже не то, на что вы соглашались».
   */
  private val onDrift: (String, ToolFingerprint.Drift) -> Unit = { _, _ -> },
  private val timeoutMs: Long = DirectChatTools.CALL_TIMEOUT_MS,
) : DirectChatTools.Source {
  private class Running(
    val entry: McpServersFile.Entry,
    val client: McpStdioClient,
    val tools: List<String>,
    /** Схемы запоминаются вместе с именами: список инструментов сервера меняется его перезапуском. */
    val specs: List<ToolSpec>,
  )

  private val running = LinkedHashMap<String, Running>()

  @Synchronized
  override fun specs(): List<ToolSpec> {
    val wanted = servers().filterNot { it.disabled }
    // Сервер, исчезнувший из файла или выключенный, гасится: живой процесс, о котором никто не
    // просил, — это утечка, которую видно только в диспетчере задач.
    running.keys.toList().filter { name -> wanted.none { it.name == name } }.forEach { stop(it) }
    val specs = ArrayList<ToolSpec>()
    val failures = ArrayList<String>()
    for (entry in wanted) {
      val alive = running[entry.name]?.takeIf { it.client.isAlive }
      if (alive == null) {
        stop(entry.name)
        val started = runCatching {
          val client = McpStdioClient.start(entry.command, entry.args, workingDir, entry.env)
          client.initialize(clientVersion, timeoutMs)
          val tools = client.listTools(timeoutMs)
          val specs = tools.map { ToolSpec(it.name, it.description, it.inputSchema) }
          // Сверка с тем, на что человек соглашался. Набор, изменившийся после одобрения, модели
          // НЕ отдаётся: согласие давалось на другие описания, а решает человек по ним.
          val project = workingDir?.toString()
          val drift = ApprovedTools.drift(project, entry.name, specs)
          when {
            ApprovedTools.isFirstSight(project, entry.name) -> {
              // Первая встреча: одобрять нечего, набор запоминается как отправная точка. Права
              // при этом прежние — чужой инструмент спрашивается на каждый вызов ([riskOf]).
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
              // Сервер остаётся запущенным (его ещё подтвердят), но инструментов не даёт.
              running[entry.name] = Running(entry, client, emptyList(), emptyList())
              emptyList()
            }
          }
        }.getOrElse { error ->
          failures += entry.name + ": " + (error.message ?: error.javaClass.simpleName)
          emptyList()
        }
        specs += started
      }
      else {
        // Список инструментов живого сервера НЕ перезапрашивается: он меняется только вместе с
        // самим сервером, а лишний круг по stdio — это задержка в начале каждого хода.
        specs += alive.specs
      }
    }
    // Жалоба НЕ отменяет остальных: источник, бросивший исключение, отдаёт панели пустой список —
    // то есть один сервер с опечаткой в команде забирал с собой инструменты всех соседей. Поэтому
    // о неудачах докладываем отдельно, а собранное отдаём (перечитка 18.09.2026).
    if (failures.isNotEmpty()) onFailure(failures)
    return specs
  }

  override fun riskOf(tool: String): McpProtocol.Risk = McpProtocol.Risk.WRITE

  override fun call(tool: String, arguments: JsonObject): McpStdioClient.CallResult {
    val owner = synchronized(this) { running.values.firstOrNull { tool in it.tools && it.client.isAlive } }
      ?: throw McpStdioClient.McpException("сервер этого инструмента не запущен: $tool")
    return owner.client.callTool(tool, arguments, timeoutMs)
  }

  @Synchronized
  override fun close() {
    running.keys.toList().forEach { stop(it) }
  }

  private fun stop(name: String) {
    running.remove(name)?.let { runCatching { it.client.close() } }
  }
}
