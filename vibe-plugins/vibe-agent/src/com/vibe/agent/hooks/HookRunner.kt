// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.hooks

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.project.Project
import com.vibe.agent.settings.VibeAgentSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Runs `.vibe/hooks.json` around agent tool calls, VibeIDE contract.
 *
 * TRIPLE GATE, all required before a single hook runs: the setting
 * `vibe.agent.hooks.enabled`, Workspace Trust, and a file that parsed cleanly.
 * Off by default so cloning a foreign repo never executes its code.
 *
 * ARCHITECTURAL NOTE (differs from VibeIDE): here the agent loop lives inside
 * Claude Code, so a preToolUse hook can only block at the two points the client
 * controls — a permission request and a client-side file write. Tools the agent
 * runs internally (Bash, its own edit tools) bypass preToolUse blocking unless
 * the permission mode makes the agent ask. post/turnEnd are fully observable.
 *
 * The parsed config is cached and invalidated by the file's mtime. Any failure
 * of the machinery itself is swallowed to NOTHING (never a block) — a bug in the
 * hook runner is ours, not the project's policy.
 */
class HookRunner(private val project: Project, private val onWarning: (String) -> Unit) {
  private val base: String? = project.basePath
  private val hooksFile: Path? = base?.let { Path.of(it, ".vibe", "hooks.json") }
  private var cached: List<Hook> = emptyList()
  private var cachedMtime: Long = -1L
  private val reportedBroken = HashSet<String>()

  /** True when the file exists but the setting is off — the panel offers to enable once. */
  fun hasHooksButDisabled(): Boolean =
    !VibeAgentSettings.hooksEnabled && hooksFile?.let { Files.isRegularFile(it) } == true

  /**
   * Drop a commented example next to a project that already uses `.vibe/`, so the
   * format is discoverable without reading the spec. Never creates `.vibe/` itself
   * (that would litter every opened project) and never overwrites an existing file.
   */
  fun seedExampleIfNeeded() {
    val root = base ?: return
    val vibeDir = Path.of(root, ".vibe")
    if (!Files.isDirectory(vibeDir)) return
    val example = vibeDir.resolve("hooks.example.jsonc")
    if (Files.exists(example)) return
    runCatching { Files.writeString(example, EXAMPLE) }
  }

  fun run(event: HookEvent, tool: String?, params: JsonObject?, changedFiles: List<String>): HookDecision {
    return try {
      if (!VibeAgentSettings.hooksEnabled) return NOTHING
      if (!TrustedProjects.isProjectTrusted(project)) return NOTHING
      val cwd = base ?: return NOTHING
      val hooks = HookConfig.hooksFor(load(), event, tool)
      if (hooks.isEmpty()) return NOTHING
      val payload = buildPayload(event, tool, params, cwd, changedFiles)
      val results = ArrayList<HookResult>()
      for (hook in hooks) {
        val result = execute(hook, payload, cwd)
        results.add(result)
        if (result.verdict == HookVerdict.REFUSE) break // first refusal stops the chain
      }
      results.filter { it.verdict == HookVerdict.BROKEN }.forEach { notifyBrokenOnce(it.message) }
      HookOutcome.decideHooks(event, results)
    }
    catch (e: Exception) {
      onWarning("хуки: сбой механизма (${e.message}) — ход не заблокирован")
      NOTHING
    }
  }

  private fun load(): List<Hook> {
    val file = hooksFile ?: return emptyList()
    if (!Files.isRegularFile(file)) { cached = emptyList(); cachedMtime = -1L; return emptyList() }
    val mtime = Files.getLastModifiedTime(file).toMillis()
    if (mtime != cachedMtime) {
      cached = HookConfig.parse(Files.readString(file), onWarning)
      cachedMtime = mtime
    }
    return cached
  }

  private fun buildPayload(event: HookEvent, tool: String?, params: JsonObject?, cwd: String, changedFiles: List<String>): String =
    buildJsonObject {
      put("event", event.wire)
      tool?.let { put("tool", it) }
      params?.let { put("params", it) }
      put("cwd", cwd)
      if (event == HookEvent.TURN_END) put("changedFiles", JsonArray(changedFiles.map { JsonPrimitive(it) }))
    }.toString()

  private fun execute(hook: Hook, payload: String, cwd: String): HookResult {
    val command = shellCommand(hook.command)
    val pb = ProcessBuilder(command)
    pb.directory(java.io.File(cwd))
    pb.environment()["VIBE_HOOK_EVENT"] = hook.event.wire
    pb.environment()["VIBE_HOOK_TOOL"] = if (hook.event == HookEvent.TURN_END) "" else (hook.tools.firstOrNull() ?: "")
    return try {
      val process = pb.start()
      // Drain both pipes CONCURRENTLY, or a process that fills the stderr buffer while we read stdout
      // deadlocks; and bound the wait BEFORE reading, or a hanging process outlives its timeout.
      val out = drain(process.inputStream)
      val err = drain(process.errorStream)
      runCatching { process.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) } }
      val finished = process.waitFor(hook.timeoutMs, TimeUnit.MILLISECONDS)
      if (!finished) process.destroyForcibly() // closes pipes → drain threads reach EOF
      val stdout = out.get(2, TimeUnit.SECONDS)
      val stderr = err.get(2, TimeUnit.SECONDS)
      HookOutcome.verdictOf(hook, if (finished) process.exitValue() else null,
        timedOut = !finished, spawnFailed = false, stdout = stdout, stderr = stderr)
    }
    catch (e: Exception) {
      HookOutcome.verdictOf(hook, null, timedOut = false, spawnFailed = true, stdout = "", stderr = e.message ?: "")
    }
  }

  private fun drain(stream: java.io.InputStream): java.util.concurrent.Future<String> {
    val future = java.util.concurrent.CompletableFuture<String>()
    Thread({ future.complete(runCatching { stream.bufferedReader().readText() }.getOrDefault("")) }, "vibe-hook-drain")
      .apply { isDaemon = true }.start()
    return future
  }

  private fun shellCommand(command: String): List<String> {
    val os = System.getProperty("os.name").lowercase()
    return if (os.contains("win")) listOf(System.getenv("COMSPEC") ?: "cmd.exe", "/d", "/s", "/c", command)
           else listOf("/bin/sh", "-c", command)
  }

  private fun notifyBrokenOnce(message: String?) {
    val text = message ?: return
    if (reportedBroken.add(text)) onWarning(text)
  }

  companion object {
    private val NOTHING = HookDecision(false, null, emptyList())

    /** Seeded to `.vibe/hooks.example.jsonc`; rename to `hooks.json` and enable in settings. Spec: docs/vibe/manuals/hooksSpec.md. */
    private val EXAMPLE = """
      // Пример хуков VibeIDEA. Переименуйте в hooks.json и включите:
      // Settings -> Tools -> VibeIDEA -> Агент -> «Включить хуки проекта».
      // Полная спека: docs/vibe/manuals/hooksSpec.md
      {
        "hooks": [
          // preToolUse блокирует только когда агент спрашивает разрешение или пишет через клиента.
          { "event": "preToolUse", "command": "sh -c 'echo ok'", "tools": ["write_text_file"], "label": "пример: пропустить запись" },
          // postToolUse не отменяет сделанное — формулирует требование исправить.
          { "event": "postToolUse", "command": "npm run lint --silent", "tools": ["write_text_file"], "timeoutMs": 60000, "label": "линтер после записи" },
          // turnEnd срабатывает в конце хода; changedFiles приходит в stdin-JSON.
          { "event": "turnEnd", "command": "npm test --silent", "timeoutMs": 300000, "label": "тесты в конце хода" }
        ]
      }
    """.trimIndent()
  }
}
