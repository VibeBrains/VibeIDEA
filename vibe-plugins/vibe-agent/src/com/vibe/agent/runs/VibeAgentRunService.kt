// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.runs

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.vibe.agent.settings.VibeAgentSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps `.vibe/agent-runs.jsonl` — the file the dispatcher reads.
 *
 * Two things make it survive a crash rather than merely a clean exit: appends are plain (a line at
 * a time), and the periodic compaction writes to a temporary file and moves it into place. The
 * heartbeat is the other half: this window stamps its live runs every 30 seconds, so a run whose
 * window vanished can be told apart from a run that is simply slow.
 */
@Service(Service.Level.PROJECT)
class VibeAgentRunService(private val project: Project) : Disposable {
  /** Identity of THIS window: another window's runs are not ours to declare dead. */
  private val epoch: String = UUID.randomUUID().toString()

  private val lock = Any()
  private val lastCompactionMs = AtomicLong(0)

  private val heartbeat = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
    { runCatching { beat() } },
    AgentRunLedger.HEARTBEAT_INTERVAL_MS,
    AgentRunLedger.HEARTBEAT_INTERVAL_MS,
    TimeUnit.MILLISECONDS,
  )

  private fun file(): Path? = project.basePath?.let { Path.of(it, ".vibe", "agent-runs.jsonl") }

  val isEnabled: Boolean get() = VibeAgentSettings.runLedgerEnabled

  // --- writing ---

  /** Records the start of an unattended run and returns its id. */
  fun started(
    source: AgentRunLedger.Source,
    goal: String,
    target: String?,
    maxSteps: Int? = null,
    /** Caller key that makes a repeated start return the same run instead of doubling the work. */
    idempotencyKey: String? = null,
    /** Path prefixes this run is about; empty claims nothing (see TerritoryGuess). */
    territory: List<String> = emptyList(),
  ): String? {
    if (!isEnabled) return null
    val run = AgentRunLedger.Run(
      runId = UUID.randomUUID().toString(),
      epoch = epoch,
      source = source,
      // Only the goal line, capped — never the whole prompt: this file must not become a transcript.
      goal = goal.lineSequence().firstOrNull().orEmpty().take(MAX_GOAL_CHARS),
      status = AgentRunLedger.Status.RUNNING,
      target = target,
      startedAtMs = System.currentTimeMillis(),
      maxSteps = maxSteps,
      idempotencyKey = idempotencyKey,
      territory = territory,
    )
    append(run)
    return run.runId
  }

  /**
   * The runs already working in this corner.
   *
   * Answered from the ledger rather than from memory: the other run may belong to another window,
   * and a lock that only sees its own process is a lock that fails exactly when it is needed.
   */
  fun territoryConflicts(prefixes: List<String>, runId: String = ""): List<AgentRunLedger.Run> {
    if (!isEnabled || prefixes.isEmpty()) return emptyList()
    return TerritoryGuess.conflicts(runs(), runId, prefixes)
  }

  fun progress(runId: String?, steps: Int, changedFiles: Int) {
    update(runId) { it.copy(steps = steps, changedFiles = changedFiles, heartbeatAtMs = System.currentTimeMillis()) }
  }

  fun finished(runId: String?, status: AgentRunLedger.Status, outcome: String) {
    update(runId) {
      it.copy(
        status = status,
        outcome = outcome,
        finishedAtMs = System.currentTimeMillis(),
        heartbeatAtMs = System.currentTimeMillis(),
      )
    }
  }

  private fun update(runId: String?, edit: (AgentRunLedger.Run) -> AgentRunLedger.Run) {
    if (runId == null || !isEnabled) return
    synchronized(lock) {
      val current = read().firstOrNull { it.runId == runId } ?: return
      append(edit(current))
    }
  }

  private fun append(run: AgentRunLedger.Run) {
    val path = file() ?: return
    synchronized(lock) {
      runCatching {
        Files.createDirectories(path.parent)
        Files.writeString(
          path, AgentRunLedger.encode(run) + "\n",
          java.nio.charset.StandardCharsets.UTF_8,
          java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND,
        )
      }
      maybeCompact()
    }
  }

  /** Stamps our still-running rows so silence means a dead window, not a busy one. */
  private fun beat() {
    if (!isEnabled) return
    synchronized(lock) {
      val mine = read().filter { it.epoch == epoch && it.status == AgentRunLedger.Status.RUNNING }
      if (mine.isEmpty()) return
      val now = System.currentTimeMillis()
      mine.forEach { append(it.copy(heartbeatAtMs = now)) }
    }
  }

  // --- reading ---

  /** Folded, orphan-marked view for the dispatcher. Reads the file — call off the EDT. */
  fun runs(): List<AgentRunLedger.Run> {
    val path = file() ?: return emptyList()
    if (!Files.exists(path)) return emptyList()
    val folded = runCatching { AgentRunLedger.fold(Files.readAllLines(path)) }.getOrDefault(emptyList())
    return AgentRunLedger.markOrphans(folded, System.currentTimeMillis(), aliveEpochs = setOf(epoch))
  }

  private fun read(): List<AgentRunLedger.Run> {
    val path = file() ?: return emptyList()
    if (!Files.exists(path)) return emptyList()
    return runCatching { AgentRunLedger.fold(Files.readAllLines(path)) }.getOrDefault(emptyList())
  }

  /**
   * Rewrites the file compactly at most once an hour: the append-only log grows by a line per
   * heartbeat, and compacting on every write would turn a cheap append into a full rewrite.
   */
  private fun maybeCompact() {
    val now = System.currentTimeMillis()
    if (now - lastCompactionMs.get() < COMPACTION_INTERVAL_MS) return
    lastCompactionMs.set(now)
    val path = file() ?: return
    runCatching {
      val runs = AgentRunLedger.markOrphans(read(), now, setOf(epoch))
      val kept = AgentRunLedger.truncate(runs, now, VibeAgentSettings.runLedgerMaxRecords, VibeAgentSettings.runLedgerRetentionDays)
      val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
      Files.writeString(tmp, kept.joinToString("\n", postfix = "\n") { AgentRunLedger.encode(it) })
      Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
  }

  override fun dispose() {
    heartbeat.cancel(false)
  }

  companion object {
    private const val MAX_GOAL_CHARS = 200
    private const val COMPACTION_INTERVAL_MS = 60 * 60 * 1000L

    fun getInstance(project: Project): VibeAgentRunService = project.service()
  }
}
