// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.http

import com.vibe.agent.i18n.VibeI18n.t

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import java.util.Collections

/**
 * Bridge between the HTTP API and the agent panels of open projects.
 *
 * The API runs a task in a REAL IDE window — that is the whole premise (the agent needs the
 * project, its indexes and its `.vibe`). So the gateway keeps a registry of live panels and picks
 * one; it never spins up a headless agent behind the user's back.
 */
@Service(Service.Level.APP)
class VibeAgentGateway {
  /** Implemented by the agent panel; kept narrow so the gateway does not depend on Swing. */
  interface Target {
    val projectName: String

    /** True when the given session (a chat thread) belongs to this window's project. */
    fun ownsSession(sessionId: String): Boolean

    /**
     * Runs [task] in this window and returns the session id to continue with.
     * Blocking when [wait]; must never be called on the EDT.
     */
    fun runExternalTask(task: String, sessionId: String?, wait: Boolean): String

    /**
     * Puts text into the composer WITHOUT starting a turn — the design overlay hands over a
     * finding, and the person decides what to ask about it. Starting a turn for them would take
     * the decision away and spend a request they did not ask for.
     */
    fun putIntoComposer(text: String) {}
  }

  private val targets = Collections.synchronizedList(ArrayList<Target>())

  fun register(target: Target) {
    targets.remove(target)
    targets.add(target)
  }

  fun unregister(target: Target) {
    targets.remove(target)
  }

  val hasTargets: Boolean get() = targets.isNotEmpty()

  /**
   * Chooses a window and runs the task there.
   *
   * A session id wins over recency: continuing a conversation must land in the project that
   * conversation belongs to, even when the user has since switched windows.
   */
  fun run(task: String, sessionId: String?, wait: Boolean): String {
    val snapshot = targets.toList()
    if (snapshot.isEmpty()) {
      throw IllegalStateException(t("gateway.noPanel"))
    }
    val target = sessionId?.let { id -> snapshot.firstOrNull { it.ownsSession(id) } }
                 ?: snapshot.last() // most recently registered/used window
    val session = target.runExternalTask(task, sessionId, wait)
    register(target) // moves it to the end: the next task without a session lands here too
    return session
  }

  /** Delivers text to the most recently used window's composer; false when there is no window. */
  fun putIntoComposer(text: String): Boolean {
    val target = targets.toList().lastOrNull() ?: return false
    target.putIntoComposer(text)
    return true
  }

  companion object {
    fun getInstance(): VibeAgentGateway = ApplicationManager.getApplication().service()
  }
}
