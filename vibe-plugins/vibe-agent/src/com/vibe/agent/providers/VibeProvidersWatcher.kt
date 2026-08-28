// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import java.nio.file.Path

/**
 * Watches provider configuration on disk — `providers.json`, the `providers/` catalog
 * and `.vibe/.env` of BOTH scopes — and publishes [ProvidersChangeListener.TOPIC] so an
 * open panel re-reads the registry without an IDE restart (transfer-path item №9).
 *
 * `~/.vibe` lives outside any content root, so the VFS does not watch it by itself:
 * we register an explicit watch root and force the directory into the VFS once —
 * without both steps no events ever arrive for the global scope. Events are debounced:
 * one save often produces a burst (create+content change, temp-file renames).
 *
 * Deliberately NOT ported from VibeIDE: the last-good-registry cache. Their renderer
 * needed it to paint synchronously on startup; our panel loads off the EDT and local
 * reads take milliseconds (decision №26 — YAGNI).
 */
@Service(Service.Level.PROJECT)
class VibeProvidersWatcher(private val project: Project) : Disposable {
  private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
  private var globalWatch: LocalFileSystem.WatchRequest? = null

  fun start() {
    val home = System.getProperty("user.home")
    val globalVibe = Path.of(home, ".vibe").toString()
    val lfs = LocalFileSystem.getInstance()
    globalWatch = lfs.addRootToWatch(globalVibe, true)
    // Force the dir into the VFS so change events have a node to attach to.
    lfs.refreshAndFindFileByPath(globalVibe)
    project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
      override fun after(events: List<VFileEvent>) {
        val relevant = events.any { ProvidersWatchPaths.matches(it.path, project.basePath, home) }
        if (!relevant) return
        alarm.cancelAllRequests()
        alarm.addRequest({
          if (!project.isDisposed) {
            project.messageBus.syncPublisher(ProvidersChangeListener.TOPIC).providersChanged()
          }
        }, DEBOUNCE_MS)
      }
    })
  }

  override fun dispose() {
    globalWatch?.let { LocalFileSystem.getInstance().removeWatchedRoot(it) }
  }

  private companion object {
    /** One save is a burst of VFS events (temp files, create+change) — collapse it. */
    const val DEBOUNCE_MS = 500
  }
}

/** Project open: arm the watcher (project service, disposed with the project). */
class VibeProvidersWatcherStarter : ProjectActivity {
  override suspend fun execute(project: Project) {
    project.getService(VibeProvidersWatcher::class.java).start()
  }
}
