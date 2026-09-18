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
  private val timeoutMs: Long = DirectChatTools.CALL_TIMEOUT_MS,
) : DirectChatTools.Source {
  private class Running(val entry: McpServersFile.Entry, val client: McpStdioClient, val tools: List<String>)

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
          running[entry.name] = Running(entry, client, tools.map { it.name })
          tools.map { ToolSpec(it.name, it.description, it.inputSchema) }
        }.getOrElse { error ->
          failures += entry.name + ": " + (error.message ?: error.javaClass.simpleName)
          emptyList()
        }
        specs += started
      }
      else {
        specs += alive.client.listTools(timeoutMs).map { ToolSpec(it.name, it.description, it.inputSchema) }
      }
    }
    if (failures.isNotEmpty()) throw McpStdioClient.McpException(failures.joinToString("; "))
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
