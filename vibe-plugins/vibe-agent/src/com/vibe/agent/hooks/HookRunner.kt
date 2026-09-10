// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.hooks

import com.vibe.agent.i18n.VibeI18n.t

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.project.Project
import com.vibe.agent.settings.VibeAgentSettings
import com.vibe.agent.util.ProcessSupport
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

  // The hooks example is seeded by VibeDefaults together with the rest of `.vibe/`
  // (owner's decision 2026-08-28: unconditional environment seeding, VibeIDE model).

  /**
   * Есть ли включённый хук на это событие.
   *
   * Нужно там, где «хук промолчал» и «хука нет» — разные ответы: гейт приёмки, разрешивший шаг
   * молча (код 0, пустой stdout), неотличим от отсутствующего гейта по одному лишь решению.
   */
  fun has(event: HookEvent): Boolean {
    if (!VibeAgentSettings.hooksEnabled) return false
    if (!TrustedProjects.isProjectTrusted(project)) return false
    return runCatching { HookConfig.hooksFor(load(), event, null).isNotEmpty() }.getOrDefault(false)
  }

  fun run(
    event: HookEvent,
    tool: String?,
    params: JsonObject?,
    changedFiles: List<String>,
    /** Extra facts about THIS event, merged into the payload as-is (pipeline step, its answer). */
    context: JsonObject? = null,
  ): HookDecision {
    return try {
      if (!VibeAgentSettings.hooksEnabled) return NOTHING
      if (!TrustedProjects.isProjectTrusted(project)) return NOTHING
      val cwd = base ?: return NOTHING
      val hooks = HookConfig.hooksFor(load(), event, tool)
      if (hooks.isEmpty()) return NOTHING
      flushPendingWrites()
      val payload = buildPayload(event, tool, params, cwd, changedFiles, context)
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
      onWarning(t("hooks.warn.mechanismFailed", "reason" to e.message))
      NOTHING
    }
  }

  /**
   * Досылает на диск то, что VFS отложила, — перед тем как отдать файлы ВНЕШНЕМУ процессу.
   *
   * С 2026.2 запись `VirtualFile` асинхронна: write action заканчивается раньше, чем содержимое
   * доходит до диска. Для всех, кто ходит через VFS, это невидимо — VFS поддерживает иллюзию
   * законченной записи. Но хук — чужой процесс, он читает диск напрямую, и без сброса линтер или
   * тесты увидят ВЧЕРАШНЕЕ содержимое и промолчат: отказ будет выглядеть как «проверка не заметила
   * правку», то есть как отсутствие проблемы.
   *
   * Сбрасывается всё разом, а не по файлам: список отложенного — это ровно то, что мы сами
   * записали через клиента, и он почти всегда пуст, а собирать `VirtualFile` по путям из полезной
   * нагрузки значило бы повторять работу VFS.
   *
   * Вызывается только когда хук реально будет запущен, и только вне write action: внутри него
   * сбрасывать нечего (наша собственная запись ещё не закончена) и нельзя. Случай сегодня
   * недостижим — `preToolUse` на записи срабатывает ДО неё, — но проверка стоит дешевле разбора.
   *
   * API помечен `@ApiStatus.Experimental`; риск назван здесь, а не спрятан.
   */
  private fun flushPendingWrites() {
    val app = com.intellij.openapi.application.ApplicationManager.getApplication()
    if (app.isWriteAccessAllowed) return
    runCatching { com.intellij.openapi.vfs.newvfs.ManagingFS.getInstance().flushPendingUpdates() }
      .onFailure { onWarning(t("hooks.warn.flushFailed", "reason" to it.message)) }
  }

  // Synchronized: run() is invoked from the reader thread (preToolUse), pooled threads
  // (postToolUse, turnEnd) and the fs-write thread — the mtime cache must not tear.
  @Synchronized
  private fun load(): List<Hook> {
    val file = hooksFile ?: return emptyList()
    if (!Files.isRegularFile(file)) { cached = emptyList(); cachedMtime = -1L; return emptyList() }
    // Guard against a pathological/huge file OOMing the read (a config, not a data file).
    if (Files.size(file) > MAX_HOOKS_BYTES) {
      if (cachedMtime != -2L) { onWarning(t("hooks.warn.tooBig", "limit" to (MAX_HOOKS_BYTES / 1024))); cachedMtime = -2L }
      cached = emptyList()
      return emptyList()
    }
    val mtime = Files.getLastModifiedTime(file).toMillis()
    if (mtime != cachedMtime) {
      cached = HookConfig.parse(Files.readString(file), onWarning)
      cachedMtime = mtime
    }
    return cached
  }

  private fun buildPayload(
    event: HookEvent,
    tool: String?,
    params: JsonObject?,
    cwd: String,
    changedFiles: List<String>,
    context: JsonObject?,
  ): String =
    buildJsonObject {
      put("event", event.wire)
      tool?.let { put("tool", it) }
      params?.let { put("params", it) }
      put("cwd", cwd)
      if (event == HookEvent.TURN_END || event == HookEvent.PIPELINE_STEP_END) {
        put("changedFiles", JsonArray(changedFiles.map { JsonPrimitive(it) }))
      }
      // Верхним уровнем, а не вложенным объектом: хук читает payload построчно через jq, и лишний
      // уровень означает лишний путь в каждом скрипте.
      context?.forEach { (key, value) -> put(key, value) }
    }.toString()

  private fun execute(hook: Hook, payload: String, cwd: String): HookResult {
    val pb = ProcessBuilder(ProcessSupport.shellCommand(hook.command))
    pb.directory(java.io.File(cwd))
    pb.environment()["VIBE_HOOK_EVENT"] = hook.event.wire
    pb.environment()["VIBE_HOOK_TOOL"] =
      if (hook.event in HookConfig.EVENTS_WITHOUT_TOOLS) "" else (hook.tools.firstOrNull() ?: "")
    return try {
      val process = pb.start()
      // Drain both pipes CONCURRENTLY, or a process that fills the stderr buffer while we read stdout
      // deadlocks; and bound the wait BEFORE reading, or a hanging process outlives its timeout.
      val out = ProcessSupport.drain(process.inputStream, "vibe-hook-drain")
      val err = ProcessSupport.drain(process.errorStream, "vibe-hook-drain")
      // stdin on its own thread too: a hook that ignores stdin + a payload larger than the OS pipe
      // buffer would otherwise park THIS thread before waitFor, making the timeout unenforceable.
      Thread({ runCatching { process.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) } } }, "vibe-hook-stdin")
        .apply { isDaemon = true }.start()
      val finished = process.waitFor(hook.timeoutMs, TimeUnit.MILLISECONDS)
      if (!finished) {
        runCatching { com.intellij.execution.process.OSProcessUtil.killProcessTree(process) }
        process.destroyForcibly() // closes pipes → drain threads reach EOF
      }
      // Swallow a slow drain (e.g. a grandchild holding the pipe): the exit code is authoritative, so a
      // passing hook must NOT be mislabelled BROKEN just because the reader didn't reach EOF in time.
      val stdout = runCatching { out.get(ProcessSupport.DRAIN_JOIN_TIMEOUT_SEC, TimeUnit.SECONDS) }.getOrDefault("")
      val stderr = runCatching { err.get(ProcessSupport.DRAIN_JOIN_TIMEOUT_SEC, TimeUnit.SECONDS) }.getOrDefault("")
      HookOutcome.verdictOf(hook, if (finished) process.exitValue() else null,
        timedOut = !finished, spawnFailed = false, stdout = stdout, stderr = stderr)
    }
    catch (e: Exception) {
      HookOutcome.verdictOf(hook, null, timedOut = false, spawnFailed = true, stdout = "", stderr = e.message ?: "")
    }
  }

  private fun notifyBrokenOnce(message: String?) {
    val text = message ?: return
    if (reportedBroken.add(text)) onWarning(text)
  }

  companion object {
    private val NOTHING = HookDecision(false, flagged = false, agentMessage = null, brokenHooks = emptyList())
    private const val MAX_HOOKS_BYTES = 512L * 1024L
  }
}
