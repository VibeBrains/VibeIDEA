// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

/**
 * Every file of a skill directory, hashed — what an approval is bound to.
 *
 * The unit is the whole directory, recursively. A digest over SKILL.md and the NAMES of the entries
 * beside it let a pull request rewrite `scripts/extract_conflict_context.py` of a seeded skill and
 * ask nobody: the name stayed, so the approval stayed (found 11.09.2026). Three independent schemes
 * draw the line in the same place — NVIDIA signs the whole skill directory, OpenClaw hashes the
 * whole bundle, and arXiv 2604.02837 lists trust bound to a content hash among the defences.
 *
 * Reading happens here, where the filesystem is; the verdicts live in [SkillValidator] and
 * [SkillApproval].
 */
data class SkillFiles(
  /** Every file, sorted by path. */
  val entries: List<Entry>,
  /** Links whose target leaves the skills tree or does not exist, by relative path. */
  val escaping: List<String>,
  /** Why the walk stopped short, or null when every file was read. */
  val incomplete: Incomplete?,
  /** Text of the files under `scripts/`, for the validator's look at what they fetch and run. */
  val scripts: Map<String, String>,
) {
  data class Entry(val path: String, val sha256: String, val executable: Boolean)

  data class Incomplete(val reason: Reason, val path: String) {
    enum class Reason { FILES, BYTES, DEPTH, UNREADABLE }
  }

  /** How far the walk goes before it refuses to vouch for the directory. */
  data class Limits(val files: Int = MAX_FILES, val bytes: Long = MAX_BYTES, val depth: Int = MAX_DEPTH)

  /** Path → content hash: stored beside the approval, so the next question can say WHICH files changed. */
  val hashes: Map<String, String> get() = entries.associate { it.path to it.sha256 }

  /** Names at the top of the directory, directories with a trailing slash. */
  val topLevel: List<String>
    get() = entries.map { entry ->
      val head = entry.path.substringBefore('/')
      if ('/' in entry.path) "$head/" else head
    }.distinct()

  companion object {
    /** A skill is a recipe with a few scripts; a thousand files is a repository someone dropped in. */
    const val MAX_FILES = 1_000

    /** Room for scripts and reference files. Everything is hashed on every use, so not for datasets. */
    const val MAX_BYTES = 20L * 1024 * 1024

    const val MAX_DEPTH = 16

    /** Scripts beyond this are hashed but not scanned: the scan runs on every listing and must stay cheap. */
    const val MAX_SCRIPT_BYTES = 256 * 1024

    /** Noise left by the OS, interpreters and version control — nothing an author ships. */
    val IGNORED_DIRS = setOf("__pycache__", ".git")
    val IGNORED_FILES = setOf(".DS_Store")

    internal const val SCRIPTS_DIR = "scripts/"

    fun scan(root: Path, dir: Path, limits: Limits = Limits()): SkillFiles {
      val rootReal = try { root.toRealPath() } catch (e: IOException) { null }
      val base = try { dir.toRealPath() } catch (e: IOException) { null }
      // The skill folder may itself be a link; it is judged like any link inside it.
      if (rootReal == null || base == null || !base.startsWith(rootReal)) {
        return SkillFiles(emptyList(), listOf("${dir.fileName}/"), null, emptyMap())
      }
      val walk = Walk(rootReal, base, limits)
      try {
        Files.walkFileTree(base, emptySet(), limits.depth, walk)
      }
      catch (e: IOException) {
        walk.incomplete = walk.incomplete ?: Incomplete(Incomplete.Reason.UNREADABLE, "")
      }
      return SkillFiles(walk.entries.sortedBy { it.path }, walk.escaping.sorted(), walk.incomplete, walk.scripts.toSortedMap())
    }

    /** A link target as the digest names it: relative to the skills root, `/`-separated. */
    internal fun linkTarget(root: Path, real: Path): String = root.relativize(real).joinToString("/") { it.toString() }

    fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
  }

  private class Walk(private val root: Path, private val base: Path, private val limits: Limits) : SimpleFileVisitor<Path>() {
    val entries = ArrayList<Entry>()
    val escaping = ArrayList<String>()
    val scripts = HashMap<String, String>()
    var incomplete: Incomplete? = null
    private var bytes = 0L

    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
      if (dir != base && dir.fileName.toString() in IGNORED_DIRS) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
      val path = relative(file)
      // walkFileTree hands over a directory at the depth limit as a file instead of entering it.
      if (attrs.isDirectory) return stop(Incomplete.Reason.DEPTH, path)
      if (file.fileName.toString() in IGNORED_FILES) return FileVisitResult.CONTINUE
      if (entries.size >= limits.files) return stop(Incomplete.Reason.FILES, path)
      if (attrs.isSymbolicLink) return link(file, path)
      // Sockets and devices: nothing an agent runs.
      if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
      val content = read(file, attrs.size(), path) ?: return FileVisitResult.TERMINATE
      entries.add(Entry(path, sha256(content), executable(file, content)))
      if (path.startsWith(SCRIPTS_DIR) && content.size <= MAX_SCRIPT_BYTES && content.none { it == ZERO }) {
        scripts[path] = String(content, Charsets.UTF_8)
      }
      return FileVisitResult.CONTINUE
    }

    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
      stop(Incomplete.Reason.UNREADABLE, relative(file))

    /**
     * A link counts by its CONTENT while it stays inside the skills tree — what it points at is what
     * runs. Leaving the tree, or pointing at nothing, makes it an escape.
     *
     * The line is built from the TARGET — its path relative to the skills root — not from the text of
     * the link (agreed with VibeIDE, 13.09.2026): the VS Code file service gives a real path but never
     * the raw link text, and `../shared/x.sh` and an absolute path to the same file are one skill.
     * An escaping link keeps the text: there is no target inside the tree to name, and the validator
     * refuses such a skill anyway.
     */
    private fun link(file: Path, path: String): FileVisitResult {
      val real = try { file.toRealPath() } catch (e: IOException) { null }
      if (real == null || !real.startsWith(root)) {
        val text = try { Files.readSymbolicLink(file).toString() } catch (e: IOException) { "?" }
        escaping.add(path)
        entries.add(Entry(path, "link:$text", executable = false))
        return FileVisitResult.CONTINUE
      }
      val target = linkTarget(root, real)
      if (!Files.isRegularFile(real)) {
        entries.add(Entry(path, "link:$target:dir", executable = false))
        return FileVisitResult.CONTINUE
      }
      val size = try { Files.size(real) } catch (e: IOException) { return stop(Incomplete.Reason.UNREADABLE, path) }
      val content = read(real, size, path) ?: return FileVisitResult.TERMINATE
      entries.add(Entry(path, "link:$target:" + sha256(content), executable = false))
      return FileVisitResult.CONTINUE
    }

    private fun read(file: Path, size: Long, path: String): ByteArray? {
      if (bytes + size > limits.bytes) {
        incomplete = Incomplete(Incomplete.Reason.BYTES, path)
        return null
      }
      val content = try {
        Files.readAllBytes(file)
      }
      catch (e: IOException) {
        incomplete = Incomplete(Incomplete.Reason.UNREADABLE, path)
        return null
      }
      bytes += content.size
      return content
    }

    /** The POSIX execute bit where there is one, a shebang everywhere: either way the agent can run it. */
    private fun executable(file: Path, content: ByteArray): Boolean {
      if (content.size >= 2 && content[0] == HASH && content[1] == BANG) return true
      val permissions = try {
        Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS)
      }
      catch (e: UnsupportedOperationException) {
        return false
      }
      catch (e: IOException) {
        return false
      }
      return permissions.any {
        it == PosixFilePermission.OWNER_EXECUTE || it == PosixFilePermission.GROUP_EXECUTE || it == PosixFilePermission.OTHERS_EXECUTE
      }
    }

    private fun stop(reason: Incomplete.Reason, path: String): FileVisitResult {
      incomplete = Incomplete(reason, path)
      return FileVisitResult.TERMINATE
    }

    private fun relative(file: Path): String = base.relativize(file).joinToString("/") { it.toString() }

    private companion object {
      const val ZERO: Byte = 0
      const val HASH: Byte = '#'.code.toByte()
      const val BANG: Byte = '!'.code.toByte()
    }
  }
}
