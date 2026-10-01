// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy
import com.intellij.openapi.vfs.VfsUtilCore

/**
 * Agents' worktrees inside the project stay out of the IDE's index: ours ([StepWorktrees.DIR]) and Claude Code's
 * Each is a whole second copy of the sources, and indexed it doubles every search, every «go to» and every reindex
 */
class WorktreeExcludePolicy(private val project: Project) : DirectoryIndexExcludePolicy {
  override fun getExcludeUrlsForProject(): Array<String> {
    val base = project.basePath ?: return emptyArray()
    return DIRS.map { VfsUtilCore.pathToUrl("$base/$it") }.toTypedArray()
  }

  private companion object {
    /** Claude Code puts its worktrees under `.claude/worktrees` (code.claude.com/docs/en/worktrees) */
    val DIRS = listOf(StepWorktrees.DIR, ".claude/worktrees")
  }
}
