// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.background

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The background jobs of one project: what is running, since when, and how to stop it.
 *
 * A job you cannot see is a job you cannot stop, and `/bg` had neither. It started a shell command
 * and forgot it: no list, no cancel, no way to tell a build that is still going from one that hung
 * before we gave it a deadline. The MCP Tasks extension makes a handle mandatory for exactly this
 * reason — «task id» is not bookkeeping, it is the difference between an operation and a rumour.
 *
 * Ids are short and human-sayable (`bg-1`), because the person types them back at us.
 *
 * The registry itself is pure state plus a `stop` lambda: the process lives in the caller, so this
 * stays testable and cannot leak a handle to a dead job.
 */
class TaskRegistry {
  enum class State { RUNNING, DONE, FAILED, STOPPED, EXPIRED }

  data class Task(
    val id: String,
    val command: String,
    val startedAtMs: Long,
    val state: State = State.RUNNING,
    val finishedAtMs: Long? = null,
  ) {
    val running: Boolean get() = state == State.RUNNING

    fun ageMs(nowMs: Long): Long = (finishedAtMs ?: nowMs) - startedAtMs
  }

  private val tasks = ConcurrentHashMap<String, Task>()
  private val stoppers = ConcurrentHashMap<String, () -> Unit>()
  private val counter = AtomicInteger()

  /** Registers a started job and returns its handle. */
  fun start(command: String, startedAtMs: Long, stop: () -> Unit): Task {
    val id = ID_PREFIX + counter.incrementAndGet()
    val task = Task(id, command, startedAtMs)
    tasks[id] = task
    stoppers[id] = stop
    return task
  }

  fun finish(id: String, state: State, atMs: Long) {
    tasks.computeIfPresent(id) { _, task -> task.copy(state = state, finishedAtMs = atMs) }
    // The stopper goes with the job: keeping it would let a later «/bg stop» kill a process the
    // operating system has already given to somebody else.
    stoppers.remove(id)
  }

  /** Everything we know about, newest first — finished jobs included, until they are forgotten. */
  fun all(): List<Task> = tasks.values.sortedByDescending { it.startedAtMs }

  fun running(): List<Task> = all().filter { it.running }

  fun get(id: String): Task? = tasks[id]

  /**
   * Stops one job, or answers false when there is nothing to stop.
   *
   * False is a real answer, not a failure: «этой задачи уже нет» and «не смог остановить» are
   * different things, and the caller says which one happened.
   */
  fun stop(id: String): Boolean {
    val stopper = stoppers.remove(id) ?: return false
    stopper()
    return true
  }

  /** Stops everything that is still running; returns how many were actually stopped. */
  fun stopAll(): Int = running().count { stop(it.id) }

  /**
   * Drops finished jobs older than [keepMs], oldest first.
   *
   * A list that grows for the life of the IDE stops being a list. Running jobs are never dropped:
   * forgetting a running process is how a handle turns back into a rumour.
   */
  fun forgetFinished(nowMs: Long, keepMs: Long = KEEP_FINISHED_MS) {
    tasks.values
      .filter { !it.running && it.finishedAtMs != null && nowMs - it.finishedAtMs >= keepMs }
      .forEach { tasks.remove(it.id) }
  }

  companion object {
    const val ID_PREFIX = "bg-"

    /** Long enough to ask «что там было», short enough that the list stays readable. */
    const val KEEP_FINISHED_MS: Long = 60L * 60 * 1000
  }
}
