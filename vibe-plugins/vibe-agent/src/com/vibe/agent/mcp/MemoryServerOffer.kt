// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The shared VibeMemory store, offered to an ACP agent as a stdio MCP server.
 *
 * Decision of the owner (13.09.2026): agents started by VibeIDEA work with the common memory of the
 * product family. VibeIDE gets it through its own `mcp.json`; our agents get it here, in `session/new`.
 * Stdio and not HTTP on purpose: the ACP spec obliges every agent to support stdio MCP servers,
 * while HTTP is a capability the agent may lack.
 *
 * ONE resolver for where the server lives and which `--agent` this product signs with — the contract
 * is meant to move into the shared VibeBrains set, and until then it must not be spelled twice here.
 * The server is offered only when it exists AND answers `--version`: a record pointing at a binary that
 * cannot start makes the agent report a broken tool on every session.
 */
object MemoryServerOffer {
  const val NAME = "vibememory"

  /** The product's name in every memory record this agent writes. */
  const val AGENT_ID = "vibeidea"

  private const val PROBE_SECONDS = 3L

  enum class Reason { OFFERED, NOT_INSTALLED, NOT_RUNNING }

  data class Offer(val entry: Map<String, Any>?, val reason: Reason, val path: Path)

  /** `VIBEMEMORY_DIR` when set, otherwise `~/.vibememory`; the binary is under `bin/`. */
  fun binaryPath(vibememoryDir: String?, home: String, windows: Boolean): Path {
    val base = vibememoryDir?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: Path.of(home, ".vibememory")
    return base.resolve("bin").resolve(if (windows) "vibememory-mcp.exe" else "vibememory-mcp")
  }

  /** The `session/new` record. `env` is an empty list, not an absent key: ACP requires the field. */
  fun entry(path: Path): Map<String, Any> = mapOf(
    "name" to NAME,
    "command" to path.toString(),
    "args" to listOf("--agent", AGENT_ID),
    "env" to emptyList<Any>(),
  )

  @Volatile private var cached: Pair<String, Offer>? = null

  /**
   * The offer for this machine. The `--version` probe runs once per binary state (path + modification
   * time), not once per session: starting a process on every chat would be a cost nobody sees.
   */
  fun resolve(
    vibememoryDir: String? = System.getenv("VIBEMEMORY_DIR"),
    home: String = System.getProperty("user.home"),
    windows: Boolean = com.vibe.agent.util.ExecutableNames.isWindows(),
  ): Offer {
    val path = binaryPath(vibememoryDir, home, windows)
    if (!Files.isRegularFile(path)) return Offer(null, Reason.NOT_INSTALLED, path)
    val key = path.toString() + "@" + runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)
    cached?.takeIf { it.first == key }?.let { return it.second }
    val answers = runCatching {
      val process = ProcessBuilder(path.toString(), "--version").redirectErrorStream(true).start()
      val finished = process.waitFor(PROBE_SECONDS, TimeUnit.SECONDS)
      if (!finished) process.destroyForcibly()
      finished && process.exitValue() == 0
    }.getOrDefault(false)
    val offer = if (answers) Offer(entry(path), Reason.OFFERED, path) else Offer(null, Reason.NOT_RUNNING, path)
    cached = key to offer
    return offer
  }
}
