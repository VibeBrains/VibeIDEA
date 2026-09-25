// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.providers.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * A team's shared memory on the VibeMemory host, reached over HTTPS with a token of its own (decision №109)
 *
 * `vibememory connect --agent vibeidea` keeps, per team, a token file and a sidecar beside it:
 * `~/.vibememory/tokens/<team>/vibeidea.json` names the cabinet, the team and the server address (`mcpUrl`), never
 * the token. The token itself is asked of VibeMemory's helper, `vibememory mcp-headers <team> vibeidea`, on every
 * connection: it prints the `Authorization` header and nothing else, so the token lives in exactly one file and in
 * no configuration of ours
 *
 * The server answers with the same tool names as the local memory (`memory_save`, `memory_search`…), and a name
 * offered twice goes to the first source: a team's tools therefore carry the team in their name ([toolName])
 */
object TeamMemory {
  /** Server name as VibeMemory registers it for other clients (`server_name` in its engine) */
  fun serverName(team: String): String = "vibememory-$team"

  data class Team(val team: String, val url: String, val cabinet: String?, val tokenId: String?)

  /** A team the way VibeMemory names it: lowercase letters, digits and hyphens — anything else is not one of its files */
  private val TEAM = Regex("^[a-z0-9][a-z0-9-]{0,40}$")

  private val json = Json { ignoreUnknownKeys = true }

  /** `VIBEMEMORY_DIR` when set, otherwise `~/.vibememory` — the same root [MemoryServerOffer] reads */
  fun root(vibememoryDir: String?, home: String): Path =
    vibememoryDir?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: Path.of(home, ".vibememory")

  fun helperPath(root: Path, windows: Boolean): Path = root.resolve("bin").resolve(if (windows) "vibememory.exe" else "vibememory")

  /** The sidecar of this product's token in one team's directory */
  fun sidecarName(): String = MemoryServerOffer.AGENT_ID + ".json"

  /** Every team this machine was connected to as `vibeidea`, sorted by name; a broken sidecar is skipped */
  fun teams(root: Path): List<Team> {
    val tokens = root.resolve("tokens")
    if (!Files.isDirectory(tokens)) return emptyList()
    return runCatching {
      Files.newDirectoryStream(tokens).use { dirs ->
        dirs.filter { Files.isDirectory(it) }.mapNotNull { dir ->
          val sidecar = dir.resolve(sidecarName())
          if (!Files.isRegularFile(sidecar)) return@mapNotNull null
          runCatching { parseSidecar(Files.readString(sidecar), dir.fileName.toString()) }.getOrNull()
        }.sortedBy { it.team }
      }
    }.getOrDefault(emptyList())
  }

  /**
   * One sidecar; null when it does not name a usable server
   * The team is the sidecar's own word, the directory name only a fallback: VibeMemory names the directory after it
   */
  fun parseSidecar(text: String, directory: String): Team? {
    val o = json.parseToJsonElement(text).jsonObject
    fun field(name: String) = (o[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    val agent = field("agent")
    if (agent != null && agent != MemoryServerOffer.AGENT_ID) return null
    val team = field("team") ?: directory
    if (!TEAM.matches(team)) return null
    val url = field("mcpUrl") ?: return null
    val scheme = runCatching { java.net.URI(url).scheme?.lowercase() }.getOrNull()
    // HTTP is allowed for a server on this machine only: the token must not cross a network in the clear
    if (scheme != "https" && !(scheme == "http" && com.vibe.agent.providers.LocalAddress.isLocal(url))) return null
    return Team(team, url, field("cabinet"), field("tokenId"))
  }

  /** The helper's stdout: one JSON object of header names to values; anything else is a failure, not «no headers» */
  fun parseHeaders(stdout: String): Map<String, String> {
    val o = json.parseToJsonElement(stdout.trim()).jsonObject
    val headers = o.mapValues { (it.value as? JsonPrimitive)?.contentOrNull ?: throw IllegalArgumentException(it.key) }
    require(headers.isNotEmpty()) { "no headers" }
    return headers
  }

  /**
   * Runs the helper and returns its headers. Its stderr is the reason when it fails; by VibeMemory's contract it
   * never carries the token, and its stdout is never shown anywhere
   */
  fun headers(helper: Path, team: String, timeoutSeconds: Long = HELPER_TIMEOUT_SECONDS): Map<String, String> {
    if (!Files.isRegularFile(helper)) throw McpClient.McpException("$helper not found")
    val process = ProcessBuilder(helper.toString(), HEADERS_COMMAND, team, MemoryServerOffer.AGENT_ID).start()
    val out = process.inputStream.bufferedReader().use { it.readText() }
    val err = process.errorStream.bufferedReader().use { it.readText() }
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      throw McpClient.McpException("$HEADERS_COMMAND: no answer in $timeoutSeconds s")
    }
    if (process.exitValue() != 0) throw McpClient.McpException(err.trim().ifEmpty { "$HEADERS_COMMAND exited with ${process.exitValue()}" })
    return runCatching { parseHeaders(out) }.getOrElse { throw McpClient.McpException("$HEADERS_COMMAND printed no headers") }
  }

  /** The name a team's tool is offered under: the team goes in front, so it cannot shadow the local memory */
  fun toolName(team: String, tool: String): String = TOOL_PREFIX + team + TOOL_SEPARATOR + tool

  /** The server's own name of a tool offered as [toolName], or null when the name is not this team's */
  fun serverTool(team: String, offered: String): String? =
    offered.removePrefix(TOOL_PREFIX + team + TOOL_SEPARATOR).takeIf { it != offered && it.isNotEmpty() }

  /**
   * The `session/new` record of a team's server for an ACP agent that speaks HTTP: the same shape as the IDE's own
   * ([IdeToolsOffer]) — `headers` is an array of `{name, value}` pairs, not an object
   */
  fun acpEntry(team: Team, headers: Map<String, String>): Map<String, Any> = mapOf(
    "type" to "http",
    "name" to serverName(team.team),
    "url" to team.url,
    "headers" to headers.map { (name, value) -> mapOf("name" to name, "value" to value) },
  )

  const val HEADERS_COMMAND = "mcp-headers"
  const val TOOL_PREFIX = "team-"
  const val TOOL_SEPARATOR = "__"

  /** Longest tool name the model vendors accept; a team whose names would exceed it is reported, not cut */
  const val MAX_TOOL_NAME = 64

  private const val HELPER_TIMEOUT_SECONDS = 10L
}

/**
 * The tools of every team this machine is connected to, for the direct chat
 *
 * Teams are read from the sidecars on every turn that offers tools, so a `vibememory connect` or `disconnect` takes
 * effect without restarting the IDE. Each team keeps its own connection ([MemoryServerSource]); a team that failed is
 * reported through [onFailure] and skipped, the others keep their tools
 *
 * A refused token closes the team's connection: the next turn asks the helper again, which is how a token reissued in
 * the cabinet takes effect
 */
class TeamMemorySource(
  private val teams: () -> List<TeamMemory.Team>,
  private val connect: (TeamMemory.Team) -> McpClient,
  private val clientVersion: String,
  private val onFailure: (TeamMemory.Team, Exception) -> Unit = { _, _ -> },
  private val timeoutMs: Long = DirectChatTools.CALL_TIMEOUT_MS,
) : DirectChatTools.Source {
  private val sources = LinkedHashMap<String, MemoryServerSource>()

  @Synchronized
  override fun specs(): List<ToolSpec> {
    val wanted = teams()
    sources.keys.toList().filter { name -> wanted.none { it.team == name } }.forEach { sources.remove(it)?.close() }
    val offered = ArrayList<ToolSpec>()
    for (team in wanted) {
      val source = sources.getOrPut(team.team) { MemoryServerSource({ connect(team) }, clientVersion, timeoutMs) }
      val specs = try {
        source.specs()
      }
      catch (e: Exception) {
        if (e is McpClient.Unauthorized) source.close()
        onFailure(team, e)
        continue
      }
      val renamed = specs.map { it.copy(name = TeamMemory.toolName(team.team, it.name), description = describe(team, it.description)) }
      val tooLong = renamed.firstOrNull { it.name.length > TeamMemory.MAX_TOOL_NAME }
      if (tooLong != null) {
        onFailure(team, McpClient.McpException("tool name ${tooLong.name} is longer than ${TeamMemory.MAX_TOOL_NAME}"))
        continue
      }
      offered += renamed
    }
    return offered
  }

  override fun riskOf(tool: String): McpProtocol.Risk =
    route(tool)?.let { (_, serverTool) -> MemoryServerSource.riskOf(serverTool) } ?: McpProtocol.Risk.WRITE

  override fun call(tool: String, arguments: JsonObject): McpClient.CallResult {
    val (team, serverTool) = route(tool) ?: throw McpClient.McpException("unknown tool $tool")
    val source = synchronized(this) { sources[team] } ?: throw McpClient.McpException("team $team is not connected")
    return try {
      source.call(serverTool, arguments)
    }
    catch (e: McpClient.Unauthorized) {
      source.close()
      throw e
    }
  }

  /** The team and the server's tool name behind an offered name */
  private fun route(tool: String): Pair<String, String>? = synchronized(this) {
    sources.keys.firstNotNullOfOrNull { team -> TeamMemory.serverTool(team, tool)?.let { team to it } }
  }

  // Written for the model, like the tool descriptions themselves
  private fun describe(team: TeamMemory.Team, description: String): String =
    "[Team memory \"${team.team}\" on the VibeMemory host; every write needs an explicit project; this team's project_resolve lists the allowed ones] $description"

  @Synchronized
  override fun close() {
    sources.values.forEach { it.close() }
    sources.clear()
  }
}
