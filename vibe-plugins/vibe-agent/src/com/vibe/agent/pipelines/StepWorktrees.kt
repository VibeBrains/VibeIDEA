// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.pipelines

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * A writing step of a wave in a git worktree of its own, and its work brought back into the project afterwards
 *
 * The step's write boundary holds only for writes the agent routes through the IDE or asks about; an agent that edits
 * with its own tools and asks nothing writes past it. A worktree holds whatever the agent does: its session runs there
 * Names follow VibeIDE: `.vibe-worktrees/vibe-agent-…`, inside the repository, kept out of git by `info/exclude`
 *
 * A pipeline's steps commit nothing, so a tree from HEAD would miss what the steps before the wave did: the tree starts
 * from the run's snapshot of the working tree. Its work comes back as a patch applied three-way over the project's
 * working tree, through an index of its own — the person's index is not touched, and a clash leaves conflict markers
 * and the tree in place for a merge by hand
 */
class StepWorktrees(private val projectBase: String) {
  data class Tree(val path: String, val branch: String, val base: String)

  sealed interface Merge {
    /** The step's work is in the project */
    object Clean : Merge

    /** The step changed nothing */
    object Nothing : Merge

    /** Applied with conflict markers in [files]; the tree stays for a merge by hand */
    data class Conflict(val files: List<String>) : Merge

    /** Not applied at all; [reason] is git's own */
    data class Failed(val reason: String) : Merge
  }

  /** A worktree for [id] starting from the snapshot commit [base], or null with git's reason in [onError] */
  fun create(id: String, base: String, onError: (String) -> Unit): Tree? = synchronized(LOCK) {
    excludeFromGit()
    val branch = branchName(id)
    val path = Path.of(projectBase, DIR, branch).toString()
    val (code, out) = git(projectBase, "worktree", "add", "-b", branch, path, base)
    if (code != 0) {
      onError(out.trim())
      return null
    }
    Tree(path, branch, base)
  }

  /** Commits what the step wrote in [tree] and applies it over the project's working tree */
  fun mergeBack(tree: Tree, message: String): Merge = synchronized(LOCK) {
    if (git(tree.path, "add", "-A").first != 0) return Merge.Failed("git add")
    if (git(tree.path, "diff", "--cached", "--quiet").first == 0) return Merge.Nothing
    val (committed, commitOut) = git(tree.path, "-c", "user.name=$AUTHOR", "-c", "user.email=$AUTHOR_EMAIL",
                                     "commit", "--no-verify", "-q", "-m", message)
    if (committed != 0) return Merge.Failed(commitOut.trim())
    val (diffCode, patch) = git(projectBase, "diff", "--binary", tree.base, tree.branch)
    if (diffCode != 0) return Merge.Failed(patch.trim())
    if (patch.isBlank()) return Merge.Nothing
    withScratchIndex { env ->
      // The scratch index holds the working tree as it is now, so the three-way apply sees the person's edits as theirs
      if (git(projectBase, "add", "-A", env = env).first != 0) return@withScratchIndex Merge.Failed("git add")
      val (applied, out) = git(projectBase, "apply", "--3way", "--binary", "-", env = env, input = patch)
      when {
        applied == 0 -> Merge.Clean
        else -> conflicts(out).takeIf { it.isNotEmpty() }?.let { Merge.Conflict(it) } ?: Merge.Failed(out.trim())
      }
    }
  }

  /** Removes [tree] and its branch: its work is in the project, or was nothing */
  fun remove(tree: Tree) = synchronized(LOCK) {
    git(projectBase, "worktree", "remove", "--force", tree.path)
    git(projectBase, "branch", "-D", tree.branch)
  }

  /** `.vibe-worktrees/` in `info/exclude`: the trees live in the repository and must not show as its untracked files */
  private fun excludeFromGit() {
    val (code, out) = git(projectBase, "rev-parse", "--git-path", "info/exclude")
    if (code != 0) return
    val exclude = Path.of(projectBase).resolve(out.trim())
    val current = runCatching { Files.readString(exclude) }.getOrDefault("")
    if (current.lineSequence().any { it.trim() == "/$DIR/" }) return
    Files.createDirectories(exclude.parent)
    Files.writeString(exclude, (if (current.isEmpty() || current.endsWith("\n")) current else current + "\n") + "/$DIR/\n")
  }

  private fun <T> withScratchIndex(block: (Map<String, String>) -> T): T {
    val dir = Files.createTempDirectory("vibe-worktree")
    val index = dir.resolve("index")
    try {
      git(projectBase, "rev-parse", "--git-path", "index").let { (code, out) ->
        val real = if (code == 0) Path.of(projectBase).resolve(out.trim()) else null
        if (real != null && Files.isRegularFile(real)) Files.copy(real, index)
      }
      return block(mapOf("GIT_INDEX_FILE" to index.toString()))
    }
    finally {
      runCatching {
        Files.deleteIfExists(index)
        Files.deleteIfExists(dir.resolve("index.lock"))
        Files.deleteIfExists(dir)
      }
    }
  }

  private fun git(dir: String, vararg args: String, env: Map<String, String> = emptyMap(), input: String? = null): Pair<Int, String> =
    try {
      val pb = ProcessBuilder(listOf("git", "-C", dir) + args)
      pb.redirectErrorStream(true)
      pb.environment().putAll(env)
      val p = pb.start()
      val out = com.vibe.agent.util.ProcessSupport.drain(p.inputStream, "vibe-worktree-git-drain")
      if (input != null) p.outputStream.use { it.write(input.toByteArray()) } else p.outputStream.close()
      if (!p.waitFor(GIT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
        p.destroyForcibly()
        -1 to "timeout"
      }
      else p.exitValue() to out.get(GIT_TIMEOUT_SEC, TimeUnit.SECONDS)
    }
    catch (e: Exception) {
      -1 to (e.message ?: e.javaClass.simpleName)
    }

  companion object {
    /** Where the trees live: inside the repository, out of git and out of the IDE's index */
    const val DIR = ".vibe-worktrees"

    /** By this prefix a step's branch is made and recognised, as in VibeIDE */
    const val BRANCH_PREFIX = "vibe-agent-"

    private const val AUTHOR = "VibeIDEA"
    private const val AUTHOR_EMAIL = "vibeidea@localhost"
    private const val GIT_TIMEOUT_SEC = 120L
    private const val MAX_NAME = 40
    private val LOCK = Any()

    /**
     * The branch of [id], as VibeIDE names it: letters of any language and digits stay, the rest becomes `-`,
     * and what git refuses in a branch name (`..`, a leading or trailing dot or dash, `.lock` at the end) is mended here
     */
    fun branchName(id: String): String {
      val safe = id.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}._-]+"), "-")
        .replace(Regex("\\.{2,}"), ".")
        .replace(Regex("^[-.]+|[-.]+$"), "")
        .replace(Regex("\\.lock$"), "lock")
        .take(MAX_NAME)
      return BRANCH_PREFIX + safe.ifEmpty { "session" }
    }

    /**
     * [path] written in the tree at [treePath], as the same file of the project at [projectBase]; any other unchanged
     * The steps after the wave read the files the step changed, and they are the project's files once its work is back
     */
    fun rebase(path: String, treePath: String, projectBase: String): String {
      val tree = treePath.trimEnd('/')
      return when {
        path == tree -> projectBase
        path.startsWith("$tree/") -> projectBase.trimEnd('/') + path.substring(tree.length)
        else -> path
      }
    }

    /** Files `git apply --3way` left with conflict markers, from its output */
    fun conflicts(output: String): List<String> =
      Regex("Applied patch to '(.+?)' with conflicts").findAll(output).map { it.groupValues[1] }.toList()
  }
}
