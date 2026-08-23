// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlinx.serialization.json.Json
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
)

/**
 * Reads the agent registry from `~/.jetbrains/acp.json` — the same format the
 * JetBrains ACP integration uses, so agents configured once work in both IDEs.
 * VibeIDE contract: a single malformed entry is skipped with a warning and
 * never breaks the whole registry; a missing file means defaults.
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

  fun load(onWarning: (String) -> Unit = {}): List<AgentServerConfig> {
    val path = configPath()
    if (!Files.isRegularFile(path)) return DEFAULT_AGENTS
    val result = ArrayList<AgentServerConfig>()
    try {
      val root = json.parseToJsonElement(Files.readString(path)).jsonObject
      val servers = root["agent_servers"]?.jsonObject ?: return DEFAULT_AGENTS
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
          onWarning("acp.json: запись '$name' пропущена: ${e.message}")
        }
      }
    }
    catch (e: Exception) {
      onWarning("acp.json не разобран (${e.message}) — использую агентов по умолчанию")
      return DEFAULT_AGENTS
    }
    return result.ifEmpty { DEFAULT_AGENTS }
  }
}
