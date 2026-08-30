// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.git

/**
 * The state of the repository as a fact the agent can be told, instead of a terminal session it
 * has to run and read.
 *
 * Why this exists at all: asked «что изменилось», a model without this reaches for the terminal,
 * pays for `git status`, then for `git diff`, then re-reads a diff it already half-remembers —
 * three round trips and a wall of output for four lines of answer. Worse, `git diff` on a large
 * change is exactly the kind of output that fills a window and makes the model forget the task.
 *
 * Parsing is pure and lives here so it can be tested against real git output without a repository:
 * every quirk below (rename arrows, binary files, a detached HEAD, a branch with no upstream) is a
 * shape git actually prints, and each one used to be a crash or a lie somewhere.
 */
object RepoState {
  /** One changed file: what happened to it and how big the change is. */
  data class Change(
    val path: String,
    /** Two-letter porcelain code as git prints it, e.g. `M `, ` M`, `??`, `R `. */
    val status: String,
    val added: Int = 0,
    val removed: Int = 0,
    val binary: Boolean = false,
  ) {
    val staged: Boolean get() = status.firstOrNull()?.let { it != ' ' && it != '?' } ?: false
    val untracked: Boolean get() = status == "??"
    val total: Int get() = added + removed
  }

  data class Commit(val hash: String, val subject: String)

  data class State(
    val branch: String?,
    val upstream: String?,
    val ahead: Int = 0,
    val behind: Int = 0,
    val changes: List<Change> = emptyList(),
    val commits: List<Commit> = emptyList(),
    val detached: Boolean = false,
  ) {
    val isClean: Boolean get() = changes.isEmpty()
  }

  /** `git status --porcelain=v1 -b -z` is nicer to parse, but v1 with newlines is what people paste. */
  fun parseStatus(text: String): Pair<String?, List<Change>> {
    var branch: String? = null
    val changes = ArrayList<Change>()
    for (line in text.lines()) {
      if (line.isBlank()) continue
      if (line.startsWith("## ")) { branch = line.removePrefix("## ").trim(); continue }
      if (line.length < 4) continue
      val status = line.substring(0, 2)
      var path = line.substring(3).trim()
      // A rename prints «old -> new»; the new name is the one that exists now.
      val arrow = path.indexOf(" -> ")
      if (arrow >= 0) path = path.substring(arrow + 4)
      changes.add(Change(path.trim('"'), status))
    }
    return branch to changes
  }

  /** `## main...origin/main [ahead 2, behind 1]` — and the shapes without an upstream or a branch. */
  fun parseBranchLine(line: String?): State {
    if (line.isNullOrBlank()) return State(branch = null, upstream = null)
    if (line.startsWith("HEAD (no branch)")) return State(branch = null, upstream = null, detached = true)
    val bracket = line.indexOf(" [")
    val head = if (bracket >= 0) line.substring(0, bracket) else line
    val tracking = if (bracket >= 0) line.substring(bracket + 2).trimEnd(']') else ""
    val split = head.indexOf("...")
    val branch = (if (split >= 0) head.substring(0, split) else head).trim()
    val upstream = if (split >= 0) head.substring(split + 3).trim().ifEmpty { null } else null
    val ahead = Regex("ahead (\\d+)").find(tracking)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val behind = Regex("behind (\\d+)").find(tracking)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    return State(branch = branch.ifEmpty { null }, upstream = upstream, ahead = ahead, behind = behind)
  }

  /** `git diff --numstat`: `12  3  path` and `-  -  path` for a binary file. */
  fun parseNumstat(text: String): Map<String, Pair<Int, Int>> {
    val result = LinkedHashMap<String, Pair<Int, Int>>()
    for (line in text.lines()) {
      if (line.isBlank()) continue
      val parts = line.split('\t')
      if (parts.size < 3) continue
      val added = parts[0].toIntOrNull() ?: -1
      val removed = parts[1].toIntOrNull() ?: -1
      // A rename in numstat prints `old => new` inside the path; keep the destination.
      val path = parts[2].substringAfter("=> ").trim().trim('"')
      result[path] = added to removed
    }
    return result
  }

  fun parseLog(text: String): List<Commit> = text.lines().mapNotNull { line ->
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return@mapNotNull null
    val space = trimmed.indexOf(' ')
    if (space <= 0) Commit(trimmed, "") else Commit(trimmed.substring(0, space), trimmed.substring(space + 1))
  }

  /** Joins the three outputs into one state; sizes are attached where git reported them. */
  fun assemble(statusText: String, numstatText: String, logText: String): State {
    val (branchLine, changes) = parseStatus(statusText)
    val base = parseBranchLine(branchLine)
    val sizes = parseNumstat(numstatText)
    val withSizes = changes.map { change ->
      val size = sizes[change.path] ?: return@map change
      if (size.first < 0 || size.second < 0) change.copy(binary = true)
      else change.copy(added = size.first, removed = size.second)
    }
    return base.copy(changes = withSizes, commits = parseLog(logText))
  }

  /**
   * The report itself: sorted by size, capped, and honest about the cap.
   *
   * Sorting by lines changed rather than by name is the whole value — «что тут вообще произошло»
   * is answered by the three biggest files, not by the alphabet. A silent cap would be a lie of
   * the same family as silent truncation, so the tail is counted out loud.
   */
  fun report(state: State, limit: Int, labels: Labels): String = buildString {
    appendLine(labels.header(state.branch, state.upstream, state.ahead, state.behind, state.detached))
    if (state.isClean) {
      appendLine(labels.clean)
    }
    else {
      val sorted = state.changes.sortedWith(compareByDescending<Change> { it.total }.thenBy { it.path })
      for (change in sorted.take(limit)) appendLine(labels.change(change))
      if (sorted.size > limit) appendLine(labels.more(sorted.size - limit))
    }
    if (state.commits.isNotEmpty()) {
      appendLine(labels.commitsHeader)
      for (commit in state.commits) appendLine("  ${commit.hash} ${commit.subject}")
    }
  }.trimEnd()

  /** Every user-visible word comes from the caller's catalogue — this object stays language-free. */
  interface Labels {
    fun header(branch: String?, upstream: String?, ahead: Int, behind: Int, detached: Boolean): String
    val clean: String
    fun change(change: Change): String
    fun more(count: Int): String
    val commitsHeader: String
  }
}
