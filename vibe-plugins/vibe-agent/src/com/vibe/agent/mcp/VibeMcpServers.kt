// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.mcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The window between the chat that runs the servers of `.vibe/mcp.json` and the settings page that shows them
 *
 * The chat panel owns the servers ([ConfiguredServersSource]) and lives in a tool window the settings dialog knows nothing of.
 * Without a tool window opened in this session there is no source, and the page says the servers have not been started
 * rather than inventing a state
 */
@Service(Service.Level.PROJECT)
class VibeMcpServers {
  @Volatile private var source: ConfiguredServersSource? = null
  private val listeners = CopyOnWriteArrayList<() -> Unit>()

  fun attach(source: ConfiguredServersSource) {
    this.source = source
    changed()
  }

  /** The panel that took its servers away; a newer source, if there already is one, stays */
  fun detach(source: ConfiguredServersSource) {
    if (this.source !== source) return
    this.source = null
    changed()
  }

  fun report(): ConfiguredServersSource.Report = source?.report() ?: ConfiguredServersSource.Report.EMPTY

  /** Whether a chat holds the servers, so that one of them can be restarted */
  val canRestart: Boolean get() = source != null

  /** Blocks until the server answers or fails: not for the UI thread */
  fun restart(name: String) {
    source?.restart(name)
  }

  /** Tell the source that the tools of [name] were confirmed; it lets them through to the model if the set is the confirmed one */
  fun confirmed(name: String) {
    source?.reconsider(name)
  }

  /** [listener] is called on whatever thread made the change, and goes away with [parent] */
  fun onChange(parent: Disposable, listener: () -> Unit) {
    listeners.add(listener)
    Disposer.register(parent) { listeners.remove(listener) }
  }

  /** Called by the source after every change of its report */
  fun changed() {
    listeners.forEach { it() }
  }

  companion object {
    fun getInstance(project: Project): VibeMcpServers = project.getService(VibeMcpServers::class.java)
  }
}
