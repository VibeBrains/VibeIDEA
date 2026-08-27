// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.gates

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project

/**
 * Latching security circuit breakers, persisted per project so they SURVIVE an
 * IDE restart on purpose (VibeIDE contract). The two protective breakers —
 * `secret-leak` and `protected-path` — latch on their first trip and never
 * auto-recover; only a deliberate user action clears them. While any is open,
 * the agent is not allowed to START a new turn.
 */
@Service(Service.Level.PROJECT)
@State(name = "VibeAgentBreakers", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class VibeBreakerService : PersistentStateComponent<VibeBreakerService.State> {

  class Tripped {
    @JvmField var id: String = ""
    @JvmField var reason: String = ""
    @JvmField var atMillis: Long = 0L
  }

  class State {
    @JvmField var tripped: MutableList<Tripped> = ArrayList()
  }

  private var state = State()

  override fun getState(): State = state
  override fun loadState(loaded: State) { state = loaded }

  /** Trip a protective breaker (idempotent per id — the first reason is kept). */
  @Synchronized
  fun trip(id: String, reason: String, nowMillis: Long): Boolean {
    if (state.tripped.any { it.id == id }) return false
    state.tripped.add(Tripped().apply { this.id = id; this.reason = reason; this.atMillis = nowMillis })
    return true
  }

  @Synchronized
  fun isBlocking(): Boolean = state.tripped.isNotEmpty()

  @Synchronized
  fun openReasons(): List<String> = state.tripped.map { "${it.id}: ${it.reason}" }

  /** Manual clear (all breakers). Returns how many were cleared. */
  @Synchronized
  fun clearAll(): Int {
    val n = state.tripped.size
    state.tripped = ArrayList()
    return n
  }

  companion object {
    const val SECRET_LEAK = "secret-leak"
    const val PROTECTED_PATH = "protected-path"
    fun getInstance(project: Project): VibeBreakerService = project.getService(VibeBreakerService::class.java)
  }
}
