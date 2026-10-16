// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.checkpoints

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A checkpoint of the feed: the label and the time are the journal's, the commit is git's
 *
 * [available] is false for an entry whose snapshot git no longer has: it stays in the feed, it cannot be rolled back to
 */
data class Checkpoint(
  val hash: String,
  val label: String,
  val atMillis: Long,
  val threadId: String? = null,
  val available: Boolean = true,
)

/** What a rollback is going to do, and whether the folder differs from the snapshot at all */
data class FolderRollback(val plan: RestorePlan, val wouldChange: Boolean)

/**
 * Checkpoints of the turn, by the contract shared with VibeIDE (`checkpointSpec.md` of the defaults set)
 *
 * A snapshot is a git commit without a parent whose tree is the WHOLE working folder, taken through a temporary
 * index: the user's real index, HEAD and branches are never touched
 * A reference in `refs/vibe/checkpoints/` keeps the commit from `git gc`
 * `.vibe/local/checkpoints.jsonl` stays the feed's journal (label, time), and the commit is held by the reference
 *
 * Rolling the folder back is a separate act: a plan first, the person's «yes» with the number of files to delete,
 * then the restore
 * A folder that is not a git repository degrades to «no checkpoints», never to an error
 */
class CheckpointService(private val projectBase: String) {
  private val json = Json { ignoreUnknownKeys = true }
  private val logFile: Path = com.vibe.agent.defaults.VibeLocal.file(projectBase, "checkpoints.jsonl")

  @Volatile private var cachedRoot: Path? = null

  /** Snapshots git no longer has: asked once, the answer does not come back */
  private val lost = ConcurrentHashMap.newKeySet<String>()

  fun isGitRepo(): Boolean = git(Path.of(projectBase), "rev-parse", "--is-inside-work-tree").first == 0

  /**
   * Snapshot the working folder; null when unavailable (no git, git did not answer, the tree is empty)
   *
   * [threadId] is the thread of the turn: a turn that changed nothing since the thread's previous snapshot reuses it,
   * and only the journal learns the new label and time
   * [agent] is `builtin` or the id of the ACP agent
   */
  fun create(
    label: String,
    turnIndex: Int? = null,
    toolName: String? = null,
    threadId: String? = null,
    agent: String = BUILTIN_AGENT,
  ): Checkpoint? {
    val root = root() ?: return null
    val tree = withTempIndex(root) { writeTree(root, it) } ?: return null
    val previous = readJournal().firstOrNull { it.threadId == threadId }?.hash
    val reused = previous != null && CheckpointContract.reuse(tree, treeOf(root, previous))
    val hash = if (reused) {
      previous?.takeIf { ensureRef(root, it) } ?: return null
    }
    else {
      commit(root, tree, CheckpointMeta(turnIndex, toolName, threadId, agent, label)) ?: return null
    }
    val cp = Checkpoint(hash, label, System.currentTimeMillis(), threadId)
    // A failing journal write must not lose the (already created and referenced) snapshot: only the feed entry is lost
    runCatching {
      Files.createDirectories(logFile.parent)
      Files.writeString(logFile, entryJson(cp) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
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
    val root = root() ?: return null
    val tree = withTempIndex(root) { writeTree(root, it) } ?: return null
    // From the project folder: the paths are relative to it, and git reads them as pathspecs of its current directory
    val (code, out) = git(Path.of(projectBase), "diff", "--no-color", "--no-ext-diff", cp.hash, tree, "--", *paths.toTypedArray())
    return if (code == 0) out else null
  }

  /**
   * The feed's checkpoints, the newest first
   *
   * An entry written before the references existed has no reference
   * If git still has its commit, one is put on it now (the commit would not survive `git gc` without it)
   * If git does not, the entry is marked unavailable
   */
  fun list(): List<Checkpoint> {
    val entries = readJournal()
    if (entries.isEmpty()) return entries
    val root = root() ?: return entries.map { it.copy(available = false) }
    val (code, out) = git(root, "for-each-ref", "--format=%(refname)", CheckpointContract.REF_PREFIX.trimEnd('/'))
    if (code != 0) return entries
    val referenced = out.lineSequence().map { it.trim() }.filter { it.startsWith(CheckpointContract.REF_PREFIX) }
      .map { it.removePrefix(CheckpointContract.REF_PREFIX) }.toHashSet()
    return entries.map { entry ->
      when {
        entry.hash in referenced -> entry
        entry.hash in lost -> entry.copy(available = false)
        git(root, "cat-file", "-e", "${entry.hash}^{commit}").first == 0 && ensureRef(root, entry.hash) -> entry
        else -> {
          lost.add(entry.hash)
          entry.copy(available = false)
        }
      }
    }
  }

  /**
   * What rolling the folder back to [cp] would do, touching nothing
   *
   * Files of the folder that the snapshot lacks and git does not ignore are the ones to delete
   * A path that is not on disk any more, or is a directory (a submodule), is not counted
   * Null when git cannot say, and for a snapshot git no longer has
   * Paths are read with `-z`: without it git quotes names with non-ASCII characters and such files are not found on disk
   */
  fun plan(cp: Checkpoint): FolderRollback? {
    if (!cp.available) return null
    val root = root() ?: return null
    val snapshotPaths = nulSeparated(git(root, "ls-tree", "-r", "-z", "--name-only", cp.hash)) ?: return null
    val workingPaths = nulSeparated(git(root, "ls-files", "-z", "--cached", "--others", "--exclude-standard")) ?: return null
    val onDisk = workingPaths.filter {
      val file = root.resolve(it)
      Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)
    }
    // Git that cannot tell the two trees apart cannot restore one over the other either: no plan, as for a missing snapshot
    val current = withTempIndex(root) { writeTree(root, it) } ?: return null
    val wanted = treeOf(root, cp.hash) ?: return null
    return FolderRollback(CheckpointContract.restorePlan(snapshotPaths, onDisk), current != wanted)
  }

  /**
   * Overwrite the working folder with the snapshot and delete the files of [plan]; call ONLY after the person confirmed
   *
   * The snapshot goes into a temporary index and is written out from it, the real index and HEAD stay as they were
   * The deletion runs only after the snapshot was written, and takes exactly the files the person was told about
   */
  fun restore(cp: Checkpoint, plan: RestorePlan): Boolean {
    if (!cp.available) return false
    val root = root() ?: return false
    val written = withTempIndex(root) { index ->
      val env = mapOf("GIT_INDEX_FILE" to index.toString())
      git(root, "read-tree", cp.hash, env = env).first == 0 && git(root, "checkout-index", "-a", "-f", env = env).first == 0
    } ?: false
    if (!written) return false
    var clean = true
    for (path in plan.delete) {
      val file = root.resolve(path).normalize()
      if (!file.startsWith(root)) continue
      try {
        Files.deleteIfExists(file)
      }
      catch (e: java.io.IOException) {
        clean = false
      }
    }
    return clean
  }

  /**
   * Drop the references of snapshots that nothing lives on, so that `git gc` can take the commits
   *
   * Live are the entries of the journal, and the journal keeps the newest [MAX_JOURNAL_ENTRIES]:
   * a snapshot ages out of the feed first and out of git after it
   * A reference younger than an hour is spared whatever the journal says
   * Never throws and says nothing: a failed cleanup is not the person's problem
   */
  fun prune(nowMs: Long = System.currentTimeMillis()) {
    runCatching {
      val root = root() ?: return
      val live = trimJournal().map { it.hash }.toSet()
      val (code, out) = git(root, "for-each-ref", "--format=%(refname) %(committerdate:unix)", CheckpointContract.REF_PREFIX.trimEnd('/'))
      if (code != 0) return
      for (id in CheckpointContract.staleRefs(nowMs, CheckpointContract.parsePinned(out), live)) {
        runCatching { git(root, "update-ref", "-d", CheckpointContract.REF_PREFIX + id) }
      }
    }
  }

  /** The repository root: a snapshot is the whole repository, not the opened subfolder */
  private fun root(): Path? {
    cachedRoot?.let { return it }
    val (code, out) = git(Path.of(projectBase), "rev-parse", "--show-toplevel")
    if (code != 0) return null
    return out.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
      ?.let { runCatching { Path.of(it) }.getOrNull() }?.also { cachedRoot = it }
  }

  /**
   * Run [block] with a temporary index that starts as a copy of the real one
   *
   * The temporary index is a path that does not exist yet, inside a fresh directory
   * Git refuses an EMPTY index file («index file smaller than expected»), and `Files.createTempFile` creates exactly
   * that: until 11.09.2026 no checkpoint was ever taken, and the feature read as «unavailable» without a word
   * The copy of the real index is what lets git re-hash only the files that changed
   * An empty start would hash the whole tree on every message and run into the git timeout on a large repository
   * A fresh repository has no index yet, and then the temporary one stays absent
   */
  private fun <T> withTempIndex(root: Path, block: (Path) -> T): T? {
    val dir = Files.createTempDirectory("vibe-checkpoint")
    val index = dir.resolve("index")
    try {
      val real = git(root, "rev-parse", "--git-path", "index").let { (code, out) ->
        if (code == 0) out.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.let { root.resolve(it) } else null
      }
      if (real != null && Files.isRegularFile(real)) Files.copy(real, index, StandardCopyOption.REPLACE_EXISTING)
      return block(index)
    }
    finally {
      runCatching {
        Files.deleteIfExists(index)
        Files.deleteIfExists(dir.resolve("index.lock"))
        Files.deleteIfExists(dir)
      }
    }
  }

  /** The whole folder as a tree object: `add -A` into the temporary index, then `write-tree` */
  private fun writeTree(root: Path, index: Path): String? {
    val env = mapOf("GIT_INDEX_FILE" to index.toString())
    if (git(root, "add", "-A", env = env).first != 0) return null
    return objectName(git(root, "write-tree", env = env))
  }

  /** The tree of a commit; null when git cannot read it (collected, or a reference edited by someone else) */
  private fun treeOf(root: Path, commit: String): String? =
    objectName(git(root, "rev-parse", "--verify", "-q", "$commit^{tree}"))

  /**
   * The snapshot commit: no parent, the product as author and committer, the contract's message
   *
   * `commit-tree` refuses to work without an identity, and a repository may have none
   * The repository's own `user.name` must not leak into the snapshots either
   * The dates are git's
   */
  private fun commit(root: Path, tree: String, meta: CheckpointMeta): String? {
    val signer = mapOf(
      "GIT_AUTHOR_NAME" to CheckpointContract.PRODUCT, "GIT_AUTHOR_EMAIL" to CheckpointContract.SIGNER_EMAIL,
      "GIT_COMMITTER_NAME" to CheckpointContract.PRODUCT, "GIT_COMMITTER_EMAIL" to CheckpointContract.SIGNER_EMAIL,
    )
    val message = CheckpointContract.commitMessage(CheckpointContract.PRODUCT, tree, meta)
    val hash = objectName(git(root, "commit-tree", tree, "-m", message, env = signer)) ?: return null
    return hash.takeIf { ensureRef(root, it) }
  }

  /** Without the reference a snapshot is an unreachable object and `git gc` deletes it */
  private fun ensureRef(root: Path, hash: String): Boolean =
    git(root, "update-ref", CheckpointContract.REF_PREFIX + hash, hash).first == 0

  /** The object name in git's answer, which may carry warnings on other lines; null when there is none */
  private fun objectName(result: Pair<Int, String>): String? {
    if (result.first != 0) return null
    return result.second.lineSequence().map { it.trim() }.firstOrNull { CheckpointContract.isObjectName(it) }
  }

  /** The paths of a `-z` listing; null when git failed */
  private fun nulSeparated(result: Pair<Int, String>): List<String>? =
    if (result.first == 0) result.second.split('\u0000').filter { it.isNotEmpty() } else null

  private fun entryJson(cp: Checkpoint): String = buildJsonObject {
    put("hash", cp.hash)
    put("label", cp.label)
    put("at", cp.atMillis)
    cp.threadId?.let { put("thread", it) }
  }.toString()

  /** The journal, the newest entry first; a line the disk garbled is skipped */
  private fun readJournal(): List<Checkpoint> {
    if (!Files.isRegularFile(logFile)) return emptyList()
    val lines = runCatching { Files.readAllLines(logFile) }.getOrDefault(emptyList())
    return lines.mapNotNull { line ->
      runCatching {
        val o = json.parseToJsonElement(line).jsonObject
        Checkpoint(
          o.getValue("hash").jsonPrimitive.content,
          o["label"]?.jsonPrimitive?.contentOrNull ?: "",
          o["at"]?.jsonPrimitive?.longOrNull ?: 0L,
          o["thread"]?.jsonPrimitive?.contentOrNull,
        )
      }.getOrNull()
    }.reversed()
  }

  /** The journal cut to the newest [MAX_JOURNAL_ENTRIES]; rewritten only once it has outgrown the cap by a margin */
  private fun trimJournal(): List<Checkpoint> {
    val entries = readJournal()
    if (entries.size <= MAX_JOURNAL_ENTRIES + JOURNAL_TRIM_MARGIN) return entries
    val kept = entries.take(MAX_JOURNAL_ENTRIES)
    runCatching {
      val temp = logFile.resolveSibling(logFile.fileName.toString() + ".tmp")
      Files.writeString(temp, kept.reversed().joinToString("") { entryJson(it) + "\n" })
      Files.move(temp, logFile, StandardCopyOption.REPLACE_EXISTING)
    }
    return kept
  }

  private fun git(dir: Path, vararg args: String, env: Map<String, String> = emptyMap()): Pair<Int, String> {
    return try {
      val pb = ProcessBuilder(listOf("git", "-C", dir.toString()) + args)
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

  companion object {
    /** The `agent` field of a snapshot taken for the built-in chat; an ACP agent is named by its id in the registry */
    const val BUILTIN_AGENT = "builtin"

    /** The feed keeps this many checkpoints; the older ones lose their references and git may collect them */
    const val MAX_JOURNAL_ENTRIES = 500

    /** The journal is rewritten only past the cap by this much, so the appends of other windows rarely race it */
    const val JOURNAL_TRIM_MARGIN = 100

    private const val GIT_TIMEOUT_SEC = 60L
  }
}
