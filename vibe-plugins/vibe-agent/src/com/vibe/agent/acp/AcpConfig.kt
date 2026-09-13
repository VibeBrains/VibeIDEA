// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import com.vibe.agent.i18n.VibeI18n.t

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

data class AgentServerConfig(
  val name: String,
  val command: String,
  val args: List<String>,
  val env: Map<String, String>,
  /**
   * Рабочая папка агента относительно корня проекта; null — корень.
   *
   * Существует ради монорепо: агент, запущенный в `packages/web`, видит её своим корнем и не
   * ходит по соседним пакетам. Поле было описано в сиде с самого начала и не читалось.
   */
  val dir: String? = null,
  /** Spending ceilings from the project entry's `limits`; null — none declared (see [com.vibe.agent.budget.AgentLimits]). */
  val limits: com.vibe.agent.budget.AgentLimits? = null,
)

/**
 * Реестр внешних агентов из ДВУХ мест, и это не удвоение, а две разные области.
 *
 * `~/.jetbrains/acp.json` — агенты машины: формат общего реестра протокола, поэтому настроенный
 * однажды агент работает и здесь, и в других клиентах ACP. `.vibe/agents.json` — агенты ПРОЕКТА:
 * формат VibeIDE, едет в репозитории и описывает, кого зовут именно в этой работе.
 *
 * Проектный файл сильнее машинного при совпадении имени: репозиторий знает про себя больше, чем
 * общая настройка. До 09.09.2026 проектный файл не читался вовсе — сид его описывал, спека
 * обещала, а список агентов брался только из машинного. Настройка, которую человек написал и
 * которая ничего не делает, неотличима от работающей: агент просто «не появился».
 *
 * Контракт VibeIDE в обоих случаях: битая запись пропускается с предупреждением и не роняет
 * реестр, отсутствие файлов означает умолчания.
 */
object AcpConfig {
  private val json = Json { ignoreUnknownKeys = true }

  val DEFAULT_AGENTS: List<AgentServerConfig> = listOf(
    AgentServerConfig(
      name = "Claude Code",
      command = "npx",
      args = listOf("-y", "@agentclientprotocol/claude-agent-acp"),
      env = emptyMap(),
    ),
  )

  fun configPath(): Path = Path.of(System.getProperty("user.home"), ".jetbrains", "acp.json")

  /** Файл агентов проекта — формат VibeIDE, едет вместе с репозиторием. */
  fun projectPath(projectBase: String): Path = Path.of(projectBase, ".vibe", "agents.json")

  fun load(projectBase: String? = null, onWarning: (String) -> Unit = {}): List<AgentServerConfig> {
    val machine = loadMachine(onWarning)
    val project = projectBase?.let { loadProject(it, onWarning) }.orEmpty()
    if (project.isEmpty()) return machine.ifEmpty { DEFAULT_AGENTS }
    // Проектная запись сильнее машинной при совпадении имени, а порядок — сперва проектные:
    // человек, положивший агента в репозиторий, назвал того, кем работают в этой папке.
    val overridden = project.map { it.name.trim().lowercase() }.toSet()
    return project + machine.filterNot { it.name.trim().lowercase() in overridden }
  }

  private fun loadMachine(onWarning: (String) -> Unit): List<AgentServerConfig> {
    val path = configPath()
    if (!Files.isRegularFile(path)) return emptyList()
    val result = ArrayList<AgentServerConfig>()
    try {
      val root = json.parseToJsonElement(com.vibe.agent.util.VibeJsonc.strip(Files.readString(path))).jsonObject
      val servers = root["agent_servers"]?.jsonObject ?: return emptyList()
      for ((name, el) in servers) {
        try {
          val o = el.jsonObject
          result.add(AgentServerConfig(
            name = name,
            command = o.getValue("command").jsonPrimitive.content,
            args = o["args"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            env = o["env"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
          ))
        }
        catch (e: Exception) {
          onWarning(t("acp.config.entrySkipped", "name" to name, "reason" to e.message))
        }
      }
    }
    catch (e: Exception) {
      onWarning(t("acp.config.unparsed", "reason" to e.message))
      return emptyList()
    }
    return result
  }

  private fun loadProject(projectBase: String, onWarning: (String) -> Unit): List<AgentServerConfig> {
    val path = projectPath(projectBase)
    if (!Files.isRegularFile(path)) return emptyList()
    val result = ArrayList<AgentServerConfig>()
    try {
      val root = json.parseToJsonElement(com.vibe.agent.util.VibeJsonc.strip(Files.readString(path))).jsonObject
      for (el in root["agents"]?.jsonArray ?: return emptyList()) {
        val o = el as? JsonObject ?: continue
        // Запись, адресованная другому продукту, пропускается МОЛЧА: набор общий, и запись для
        // соседа — не проблема этой сборки. Машинная область (~/.jetbrains/acp.json) под правило
        // не подпадает: это конвенция платформы, а не файл общего набора.
        if (!com.vibe.agent.defaults.VibeProducts.addressedToUs(o)) continue
        val id = o["id"]?.jsonPrimitive?.contentOrNull
        try {
          // Выключенная запись остаётся документированной, но вне списка — тот же тумблер, что у
          // провайдеров: образец едет рабочим файлом, а не двойником рядом с рабочим.
          if (o["active"]?.jsonPrimitive?.booleanOrNull == false) continue
          result.add(AgentServerConfig(
            name = o["name"]?.jsonPrimitive?.contentOrNull ?: id ?: continue,
            command = o.getValue("command").jsonPrimitive.content,
            args = o["args"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            env = o["env"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
            dir = o["dir"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            limits = com.vibe.agent.budget.AgentLimits.parse(o["limits"]),
          ))
        }
        catch (e: Exception) {
          onWarning(t("acp.config.entrySkipped", "name" to (id ?: "?"), "reason" to e.message))
        }
      }
    }
    catch (e: Exception) {
      onWarning(t("acp.config.unparsed", "reason" to e.message))
      return emptyList()
    }
    return result
  }
}
