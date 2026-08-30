// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.runs

import com.vibe.agent.i18n.VibeI18n.t

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The ledger behind the dispatcher: who ran what, and how it ended.
 *
 * Only runs nobody is watching are recorded — a task that arrived over the HTTP API, a pipeline
 * step. An ordinary chat turn is deliberately absent: it already has a full transcript in the
 * history store, and a second copy of the same thing rots differently. The consequence is worth
 * saying out loud (the panel does): an empty ledger means nothing ran unattended, not that nothing
 * happened.
 *
 * Only metadata: no prompts, no model replies, no tool arguments. A ledger that quietly accumulates
 * conversation would be a privacy leak with a nice table on top.
 *
 * Pure: encoding, folding, truncation and the orphan rule live here; the file lives in the service.
 */
object AgentRunLedger {
  enum class Status { RUNNING, COMPLETED, FAILED, CANCELLED, ORPHANED }

  enum class Source { HTTP_API, PIPELINE }

  /** Missed heartbeats before a run counts as abandoned — three, not one: a busy window may skip. */
  const val MISSED_HEARTBEATS_FOR_ORPHAN = 3
  const val HEARTBEAT_INTERVAL_MS = 30_000L
  const val DEFAULT_MAX_RECORDS = 500
  const val DEFAULT_RETENTION_DAYS = 30

  data class Run(
    val runId: String,
    /** Identifies the window that owns the run: another window's silence is not this one's death. */
    val epoch: String,
    val source: Source,
    val goal: String,
    val status: Status,
    val target: String?,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
    /** Last time the owning window said it was alive. */
    val heartbeatAtMs: Long = startedAtMs,
    val steps: Int = 0,
    val maxSteps: Int? = null,
    val changedFiles: Int = 0,
    /** In words, not as a code: «исчерпан лимит шагов», not `LIMIT_EXCEEDED`. */
    val outcome: String? = null,
    /**
     * Caller-supplied key that makes a repeated start return the SAME run.
     *
     * A retry after a network hiccup, a button pressed twice, a webhook delivered twice — each of
     * them is otherwise a full second run doing the same work in the same files.
     */
    val idempotencyKey: String? = null,
    /** Path prefixes this run claimed, so two runs do not write the same corner at once. */
    val territory: List<String> = emptyList(),
  ) {
    val isFinished: Boolean get() = status != Status.RUNNING
    val needsAttention: Boolean get() = status == Status.ORPHANED || status == Status.FAILED
  }

  data class Summary(
    val running: Int,
    val completed: Int,
    val orphaned: Int,
    val failed: Int,
  ) {
    /** Always rendered, zeros included: «брошенных 0» is information, an absent line is not. */
    val attention: Int get() = orphaned + failed
  }

  private val json = Json { ignoreUnknownKeys = true }

  // --- codec ---

  fun encode(run: Run): String = buildJsonObject {
    put("runId", run.runId)
    put("epoch", run.epoch)
    put("source", run.source.name.lowercase())
    put("goal", run.goal)
    put("status", run.status.name.lowercase())
    run.target?.let { put("target", it) }
    put("startedAt", run.startedAtMs)
    run.finishedAtMs?.let { put("finishedAt", it) }
    put("heartbeatAt", run.heartbeatAtMs)
    put("steps", run.steps)
    run.maxSteps?.let { put("maxSteps", it) }
    put("changedFiles", run.changedFiles)
    run.outcome?.let { put("outcome", it) }
    // Written only when set: an empty key in every record would double the size of a file whose
    // whole point is being readable by a human with `tail`.
    run.idempotencyKey?.let { put("idempotencyKey", it) }
  }.toString()

  /** A broken line is skipped, never fatal: one bad write must not cost the whole history. */
  fun decode(line: String): Run? {
    val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
    val runId = obj.str("runId") ?: return null
    val startedAt = obj.long("startedAt") ?: return null
    return Run(
      runId = runId,
      epoch = obj.str("epoch").orEmpty(),
      source = obj.str("source")?.let { value -> Source.entries.firstOrNull { it.name.equals(value, true) } } ?: Source.PIPELINE,
      goal = obj.str("goal").orEmpty(),
      status = obj.str("status")?.let { value -> Status.entries.firstOrNull { it.name.equals(value, true) } } ?: Status.RUNNING,
      target = obj.str("target"),
      startedAtMs = startedAt,
      finishedAtMs = obj.long("finishedAt"),
      heartbeatAtMs = obj.long("heartbeatAt") ?: startedAt,
      steps = obj.int("steps") ?: 0,
      maxSteps = obj.int("maxSteps"),
      changedFiles = obj.int("changedFiles") ?: 0,
      outcome = obj.str("outcome"),
      idempotencyKey = obj.str("idempotencyKey"),
    )
  }

  /** Append-only file, so the LAST record for a runId wins; order of first appearance is kept. */
  fun fold(lines: List<String>): List<Run> {
    val byId = LinkedHashMap<String, Run>()
    for (line in lines) {
      if (line.isBlank()) continue
      val run = decode(line) ?: continue
      byId[run.runId] = run
    }
    return byId.values.toList()
  }

  // --- rules ---

  /**
   * A run is abandoned only when the window that owned it stopped saying it was alive — a fact, not
   * a suspicion. [aliveEpochs] are the windows currently running; their runs are never touched even
   * if a heartbeat is late.
   */
  fun markOrphans(runs: List<Run>, nowMs: Long, aliveEpochs: Set<String>): List<Run> {
    val threshold = HEARTBEAT_INTERVAL_MS * MISSED_HEARTBEATS_FOR_ORPHAN
    return runs.map { run ->
      if (run.status != Status.RUNNING) return@map run
      if (run.epoch in aliveEpochs) return@map run
      if (nowMs - run.heartbeatAtMs <= threshold) return@map run
      run.copy(
        status = Status.ORPHANED,
        finishedAtMs = run.heartbeatAtMs,
        outcome = t("runs.outcome.windowClosed"),
      )
    }
  }

  /**
   * Trims the ledger. An UNFINISHED run is never dropped, whatever its age or the record cap: it is
   * the only trace that something may still be going on, and losing it is exactly the failure the
   * ledger exists to prevent.
   */
  fun truncate(runs: List<Run>, nowMs: Long, maxRecords: Int, retentionDays: Int): List<Run> {
    val retentionMs = retentionDays.toLong() * 24 * 60 * 60 * 1000
    val (unfinished, finished) = runs.partition { !it.isFinished }
    val fresh = finished.filter { nowMs - (it.finishedAtMs ?: it.startedAtMs) <= retentionMs }
    val room = (maxRecords - unfinished.size).coerceAtLeast(0)
    val kept = if (fresh.size <= room) fresh else fresh.sortedBy { it.startedAtMs }.takeLast(room)
    return (unfinished + kept).sortedBy { it.startedAtMs }
  }

  fun summarize(runs: List<Run>): Summary = Summary(
    running = runs.count { it.status == Status.RUNNING },
    completed = runs.count { it.status == Status.COMPLETED },
    orphaned = runs.count { it.status == Status.ORPHANED },
    failed = runs.count { it.status == Status.FAILED || it.status == Status.CANCELLED },
  )

  /** Case-insensitive search over goal and target — the two things a person remembers. */
  fun search(runs: List<Run>, query: String): List<Run> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return runs
    return runs.filter { it.goal.lowercase().contains(needle) || it.target.orEmpty().lowercase().contains(needle) }
  }

  private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
  private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
  private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
}
