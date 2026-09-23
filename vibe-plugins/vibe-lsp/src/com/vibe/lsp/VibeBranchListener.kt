// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.BranchChangeListener
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import com.vibe.agent.i18n.VibeI18n.t

/**
 * A branch switch is a reason to rebuild the language servers' model, and the person sees it happen.
 *
 * `git checkout` changes files massively, while a language server keeps its own project model built at start. Without
 * a restart it answers for the previous branch — completions from other code, errors on vanished lines, navigation
 * into a file that is gone. LSP4IJ does forward file events, but only for the patterns a server registered itself,
 * and that does not rebuild a server's internal index.
 *
 * Why on its own and with progress rather than asking: that is how IntelliJ-based IDEs treat reindexing after a
 * checkout. The work runs in the background, shows in "Background Tasks" and can be cancelled — half a minute eaten
 * silently would read as the IDE hanging.
 */
internal class VibeBranchListener(private val project: Project) : BranchChangeListener {
  override fun branchWillChange(branchName: String) = Unit

  override fun branchHasChanged(branchName: String) {
    val manager = LanguageServerManager.getInstance(project)
    val running = BranchSwitchRefresh.SERVER_IDS
      .filter { runCatching { manager.getServerStatus(it) == ServerStatus.started }.getOrDefault(false) }
      .toSet()
    val targets = BranchSwitchRefresh.toRestart(running)
    // No server is running, so nothing to restart: they build their model on the first file open, from the new branch.
    if (targets.isEmpty()) return

    ProgressManager.getInstance().run(object : Task.Backgroundable(
      project, t("lsp.branch.refresh", "branch" to branchName), true,
    ) {
      override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = false
        targets.forEachIndexed { index, id ->
          if (indicator.isCanceled) return
          // The server name in the progress text: without it, the line does not answer "why is this taking so long".
          indicator.text = t("lsp.branch.server", "server" to id)
          indicator.fraction = index.toDouble() / targets.size
          runCatching {
            manager.stop(id)
            manager.start(id)
          }
        }
      }
    })
  }
}
