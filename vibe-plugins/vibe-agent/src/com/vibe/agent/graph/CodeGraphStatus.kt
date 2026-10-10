// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graph

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.util.concurrent.atomic.AtomicInteger

/**
 * Told when the code graph changes under an open picture: a rebuild started, reported how much it parses, or finished
 *
 * The graph is a file that a rebuild writes, and a rebuild takes minutes on a large repository
 * A picture that asked for the graph once, at opening, shows an empty canvas for exactly as long as the build runs and then stays empty
 * So the picture listens and reads again
 */
fun interface CodeGraphListener {
  fun graphChanged()

  companion object {
    val TOPIC: Topic<CodeGraphListener> = Topic.create("VibeIDEA code graph changed", CodeGraphListener::class.java)
  }
}

/**
 * What a rebuild of the graph is doing right now
 *
 * Every caller of [CodeGraphRefresh.refresh] reports here, not only the picture: the export action, the agent's tools
 * And the picture opened meanwhile must see that a build is already running instead of starting a second one
 */
@Service(Service.Level.PROJECT)
class CodeGraphStatus(private val project: Project) {
  /** How much the build in progress will parse: `stale` of `total` files, and whether it is the first run */
  data class Progress(val stale: Int, val total: Int, val firstRun: Boolean)

  private val running = AtomicInteger()

  @Volatile
  private var current: Progress? = null

  val building: Boolean get() = running.get() > 0

  /** Null until the build has counted what it has to parse */
  val progress: Progress? get() = current

  fun started() {
    running.incrementAndGet()
    publish()
  }

  fun reported(progress: Progress) {
    current = progress
    publish()
  }

  fun finished() {
    if (running.decrementAndGet() <= 0) {
      running.set(0)
      current = null
    }
    publish()
  }

  private fun publish() {
    if (!project.isDisposed) project.messageBus.syncPublisher(CodeGraphListener.TOPIC).graphChanged()
  }

  companion object {
    fun getInstance(project: Project): CodeGraphStatus = project.getService(CodeGraphStatus::class.java)
  }
}
