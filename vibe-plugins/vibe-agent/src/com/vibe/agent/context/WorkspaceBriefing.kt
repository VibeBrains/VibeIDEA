// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

import com.vibe.agent.i18n.VibeI18n.t

/**
 * Where this turn is happening, said to the model before it has to guess.
 *
 * The direct chat used to send the conversation and nothing else: no name of the project, no root,
 * no branch. A model with tools then has to work out where it is, and which tool answers that is a
 * matter of taste — the same MiniMax M3, asked what the project was, reached for `vibe_project_info` on
 * the OpenAI endpoint and for the memory store's `project_resolve` on the Anthropic one, invented
 * `/home/user` as an argument and asked the owner to type the path (caught 18.09.2026, three runs
 * each way). Neither answer is a bug of the model: nobody told it where it stood.
 *
 * So the workspace travels in the turn itself. Two consequences beyond the obvious one: the cheap
 * question costs no tool round at all, and the line that says which tool is about what removes the
 * choice that the vendors resolve differently.
 *
 * Pure on purpose — the panel hands in what it knows, so the text is testable without a project.
 */
object WorkspaceBriefing {
  /** What the IDE knows about the open project without asking any index. */
  data class Workspace(
    val name: String,
    val root: String,
    /** Current branch, or null when the folder is not a repository (or the ref is unreadable). */
    val branch: String? = null,
    val os: String = System.getProperty("os.name").orEmpty(),
  )

  /**
   * The branch out of `.git/HEAD`, read as text.
   *
   * Read ourselves rather than asked of the VCS plugin: one line of a file cannot hang, needs no
   * index and adds no dependency to carry into the distribution. A detached head has no branch —
   * the file then holds a commit, and a commit is not a name worth showing.
   */
  fun branchOfHead(headText: String?): String? {
    val line = headText?.trim()?.lineSequence()?.firstOrNull()?.trim().orEmpty()
    if (!line.startsWith(HEAD_REF_PREFIX)) return null
    // `feature/next` keeps its slash: cutting at the last one would rename half the branches.
    return line.removePrefix(HEAD_REF_PREFIX).trim().removePrefix(HEADS_PREFIX).takeIf { it.isNotBlank() }
  }

  /** The system message of the turn. */
  fun text(workspace: Workspace): String = buildString {
    appendLine(t("wire.workspace.header"))
    appendLine(t("wire.workspace.project", "name" to workspace.name))
    appendLine(t("wire.workspace.root", "path" to workspace.root))
    workspace.branch?.let { appendLine(t("wire.workspace.branch", "name" to it)) }
    workspace.os.takeIf { it.isNotBlank() }?.let { appendLine(t("wire.workspace.os", "name" to it)) }
    append(t("wire.workspace.tools"))
  }

  private const val HEAD_REF_PREFIX = "ref:"
  private const val HEADS_PREFIX = "refs/heads/"
}
