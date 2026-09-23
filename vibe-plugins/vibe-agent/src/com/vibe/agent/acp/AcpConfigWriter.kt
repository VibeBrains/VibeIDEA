// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Adds agents to `~/.jetbrains/acp.json` — the machine's file, in the format other ACP clients read
 * too, so an agent added here works there as well.
 *
 * Only a plain-JSON file is rewritten. The reader accepts comments and trailing commas, but a rewrite
 * would drop them — what the person wrote by hand is worth more than a click — so such a file is left
 * alone and the snippet to paste comes back instead. Pure: text in, text out.
 */
object AcpConfigWriter {
  sealed interface Result {
    /**
     * The new file text. [added] — the agents that were not in the file yet; [defaults] — the default
     * agents written along, because the first entry of the file replaces them.
     */
    data class Written(val text: String, val added: List<String>, val defaults: List<String>) : Result

    /** The file is not plain JSON, or its `agent_servers` is not an object; [snippet] is what to paste by hand. */
    data class Refused(val snippet: String) : Result
  }

  private const val SERVERS = "agent_servers"

  /** Strict on purpose: a comment or a trailing comma must fail the parse, not be dropped silently. */
  private val strict = Json

  private val pretty = Json { prettyPrint = true }

  /**
   * @param defaults what the IDE shows while the file names no agent ([AcpConfig.DEFAULT_AGENTS]).
   *        A file with agents replaces them, so the first write would make them vanish without a word.
   */
  fun add(existing: String?, agents: List<AgentServerConfig>, defaults: List<AgentServerConfig> = emptyList()): Result {
    val root = if (existing.isNullOrBlank()) JsonObject(emptyMap())
               else runCatching { strict.parseToJsonElement(existing) as? JsonObject }.getOrNull()
                    ?: return Result.Refused(snippet(agents))
    val current = root[SERVERS]
    if (current != null && current !is JsonObject) return Result.Refused(snippet(agents))
    val servers = LinkedHashMap((current as? JsonObject).orEmpty())
    val kept = if (servers.isEmpty()) defaults.filter { default -> agents.none { it.name == default.name } } else emptyList()
    kept.forEach { servers[it.name] = entry(it) }
    val added = agents.distinctBy { it.name }.filter { it.name !in servers }.onEach { servers[it.name] = entry(it) }.map { it.name }
    val updated = JsonObject(LinkedHashMap(root).apply { put(SERVERS, JsonObject(servers)) })
    return Result.Written(pretty.encodeToString(JsonElement.serializer(), updated) + "\n", added, kept.map { it.name })
  }

  /**
   * Moves pinned agents to the registry's newer version: in each named entry the package operand [AgentRegistry.Upgrade.fromSpec]
   * becomes [AgentRegistry.Upgrade.toSpec], and nothing else in the file changes. [Result.Written.added] lists the agents
   * moved; one that is not in this file (it comes from the project's `.vibe/agents.json` or from the defaults) is absent
   * from it and left for the person. A file with comments is not rewritten, as in [add].
   */
  fun upgrade(existing: String?, upgrades: List<AgentRegistry.Upgrade>): Result {
    val root = existing?.takeIf { it.isNotBlank() }?.let { runCatching { strict.parseToJsonElement(it) as? JsonObject }.getOrNull() }
      ?: return Result.Refused(upgradeSnippet(upgrades))
    val servers = root[SERVERS] as? JsonObject ?: return Result.Refused(upgradeSnippet(upgrades))
    val changed = LinkedHashMap(servers)
    val done = ArrayList<String>()
    for (u in upgrades) {
      val agent = changed[u.agentName] as? JsonObject ?: continue
      val args = agent["args"] as? JsonArray ?: continue
      if (args.none { (it as? JsonPrimitive)?.content == u.fromSpec }) continue
      val moved = JsonArray(args.map { if ((it as? JsonPrimitive)?.content == u.fromSpec) JsonPrimitive(u.toSpec) else it })
      changed[u.agentName] = JsonObject(LinkedHashMap(agent).apply { put("args", moved) })
      done += u.agentName
    }
    val updated = JsonObject(LinkedHashMap(root).apply { put(SERVERS, JsonObject(changed)) })
    return Result.Written(pretty.encodeToString(JsonElement.serializer(), updated) + "\n", done, emptyList())
  }

  /** What to change by hand, one line per agent: the file is the person's, the edit is theirs to make. */
  fun upgradeSnippet(upgrades: List<AgentRegistry.Upgrade>): String =
    upgrades.joinToString("\n") { upgrade ->
      "\"${upgrade.agentName}\": \"${upgrade.fromSpec}\" → \"${upgrade.toSpec}\"" +
        // A record with a registry note keeps it true: the version it names moves with the package.
        (upgrade.registry?.let { "\n\"${upgrade.agentName}\".registry.version: \"${it.version ?: upgrade.from}\" → \"${upgrade.to}\"" } ?: "")
    }

  /** The `agent_servers` fragment for these agents, ready to paste. */
  fun snippet(agents: List<AgentServerConfig>): String =
    pretty.encodeToString(JsonElement.serializer(), JsonObject(mapOf(SERVERS to JsonObject(agents.associate { it.name to entry(it) }))))

  private fun entry(agent: AgentServerConfig): JsonObject = JsonObject(buildMap {
    put("command", JsonPrimitive(agent.command))
    put("args", JsonArray(agent.args.map { JsonPrimitive(it) }))
    if (agent.env.isNotEmpty()) put("env", JsonObject(agent.env.mapValues { JsonPrimitive(it.value) }))
  })
}
