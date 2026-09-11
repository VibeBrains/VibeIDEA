// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.checkpoints

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class Checkpoint(val hash: String, val label: String, val atMillis: Long)

/**
 * VibeIDE-style checkpoints: a git snapshot of the WHOLE working tree on every
 * chat message, taken via a temporary index (the user's real index and HEAD are
 * never touched). The snapshot is a dangling commit object referenced from
 * `.vibe/checkpoints.jsonl`; restore overwrites the working tree only after an
 * explicit question — changes made outside the chat are part of the snapshot too.
 * A non-git project degrades to "checkpoints unavailable", never to an error.
 */
class CheckpointService(private val projectBase: String) {
  private val json = Json { ignoreUnknownKeys = true }
  private val logFile: Path = com.vibe.agent.defaults.VibeLocal.file(projectBase, "checkpoints.jsonl")

  fun isGitRepo(): Boolean = git("rev-parse", "--is-inside-work-tree").first == 0

  /** Snapshot the working tree; returns null when unavailable (no git, empty tree). */
  fun create(label: String): Checkpoint? {
    if (!isGitRepo()) return null
    val tree = snapshotTree() ?: return null
    val head = git("rev-parse", "HEAD").let { if (it.first == 0) it.second.trim() else null }
    val args = if (head != null) arrayOf("commit-tree", tree, "-p", head, "-m", "vibe checkpoint: $label")
               else arrayOf("commit-tree", tree, "-m", "vibe checkpoint: $label")
    val (commitCode, commitOut) = git(*args)
    if (commitCode != 0) return null
    val cp = Checkpoint(commitOut.trim(), label, System.currentTimeMillis())
    // A failing journal write must not lose the (already-created) snapshot — the commit object
    // exists in git regardless; only the .jsonl pointer is best-effort.
    runCatching {
      Files.createDirectories(logFile.parent)
      Files.writeString(logFile, buildJsonObject {
        put("hash", cp.hash)
        put("label", cp.label)
        put("at", cp.atMillis)
      }.toString() + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
    }
    return cp
  }

  /**
   * The working tree against [cp] for [paths] (relative to the project), as a unified diff.
   *
   * New and deleted files are in it: the current tree is snapshotted the way [create] does it, so a
   * file a step created — untracked by definition — is part of the answer. Null when git cannot say;
   * empty when nothing changed.
   */
  fun diff(cp: Checkpoint, paths: Collection<String>): String? {
    if (paths.isEmpty()) return ""
    if (!isGitRepo()) return null
    val tree = snapshotTree() ?: return null
    val (code, out) = git("diff", "--no-color", "--no-ext-diff", cp.hash, tree, "--", *paths.toTypedArray())
    return if (code == 0) out else null
  }

  fun list(): List<Checkpoint> {
    if (!Files.isRegularFile(logFile)) return emptyList()
    return Files.readAllLines(logFile).mapNotNull { line ->
      runCatching {
        val o = json.parseToJsonElement(line).jsonObject
        Checkpoint(
          o.getValue("hash").jsonPrimitive.content,
          o["label"]?.jsonPrimitive?.contentOrNull ?: "",
          o["at"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
      }.getOrNull()
    }.reversed()
  }

  /** Overwrite the working tree from the snapshot. Call ONLY after the user confirmed. */
  fun restore(cp: Checkpoint): Boolean {
    // restore tracked files to snapshot state; files created after the snapshot stay (no deletions «на всякий случай»)
    return git("restore", "--source", cp.hash, "--worktree", "--", ".").first == 0
  }

  /**
   * The whole working tree as a git tree object, through a temporary index: the real index is never
   * touched.
   *
   * The temporary index is a path that does not exist yet, inside a fresh directory. Git refuses an
   * EMPTY index file («index file smaller than expected»), and `Files.createTempFile` creates exactly
   * that: until 11.09.2026 no checkpoint was ever taken — [create] returned null, and the feature read
   * as «unavailable» without a word. It starts as a copy of the real index, so git re-hashes only the
   * files that changed; an empty start would hash the whole tree on every message and run into the
   * git timeout on a large repository.
   */
  private fun snapshotTree(): String? {
    val dir = Files.createTempDirectory("vibe-checkpoint")
    val index = dir.resolve("index")
    try {
      val real = git("rev-parse", "--git-path", "index").let { (code, out) ->
        if (code == 0) Path.of(projectBase).resolve(out.trim()) else null
      }
      if (real != null && Files.isRegularFile(real)) Files.copy(real, index)
      val env = mapOf("GIT_INDEX_FILE" to index.toString())
      if (git("add", "-A", env = env).first != 0) return null
      val (treeCode, treeOut) = git("write-tree", env = env)
      return if (treeCode == 0) treeOut.trim() else null
    }
    finally {
      runCatching {
        Files.deleteIfExists(index)
        Files.deleteIfExists(dir.resolve("index.lock"))
        Files.deleteIfExists(dir)
      }
    }
  }

  private fun git(vararg args: String, env: Map<String, String> = emptyMap()): Pair<Int, String> {
    return try {
      val pb = ProcessBuilder(listOf("git", "-C", projectBase) + args)
      pb.redirectErrorStream(true)
      pb.environment().putAll(env)
      val p = pb.start()
      // Drain on a side thread and bound the wait FIRST — reading to EOF inline would make the
      // timeout unreachable for a hung git (same lesson as the hook/verify runners).
      val out = com.vibe.agent.util.ProcessSupport.drain(p.inputStream, "vibe-checkpoint-git-drain")
      if (!p.waitFor(GIT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
        runCatching { com.intellij.execution.process.OSProcessUtil.killProcessTree(p) }
        p.destroyForcibly()
        return -1 to "timeout"
      }
      val text = runCatching {
        out.get(com.vibe.agent.util.ProcessSupport.DRAIN_JOIN_TIMEOUT_SEC, TimeUnit.SECONDS)
      }.getOrDefault("")
      p.exitValue() to text
    }
    catch (e: Exception) {
      -1 to (e.message ?: "")
    }
  }

  private companion object {
    const val GIT_TIMEOUT_SEC = 60L
  }
}
