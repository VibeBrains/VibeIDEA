// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * A path the agent sent, taken apart into the two names that matter.
 *
 * - [normalized]: `.` and `..` resolved lexically, the spelling otherwise kept. Reads and writes go
 *   through it. A project opened through a symlink keeps its editor documents under that spelling,
 *   and I/O through the physical path would miss unsaved edits and write past the open document.
 *   For a path without `..` the operating system arrives exactly at [canonical], so the check and
 *   the effect name the same file.
 * - [canonical]: the physical location — the deepest existing ancestor resolved through the file
 *   system (symbolic links followed) plus the part that does not exist yet. The access policy
 *   decides on it.
 */
data class AgentPath(val normalized: Path, val canonical: Path)

/**
 * Turns the agent's text into a place before anyone decides about it.
 *
 * The text lies about files in two ways: `..` walks out of the folder it seems to be in, and a
 * symbolic link makes a name inside the project point outside it. A policy that compares the text
 * answers about one file while the operating system opens another — found by reading the code on
 * 11.09.2026: `/project/../../home/me/.ssh/id_rsa` counted as a project file.
 */
object AgentPaths {
  /** Why a path was not resolved; the caller turns it into words. */
  enum class Refusal {
    /** ACP sends absolute paths only; a relative one would resolve against the IDE's own working directory. */
    NOT_ABSOLUTE,
    /** Not a path on this platform at all. */
    INVALID,
    /** An entry on the way exists but cannot be followed: a dangling or unreadable link. */
    UNRESOLVABLE,
  }

  sealed interface Result {
    data class Resolved(val path: AgentPath) : Result
    data class Refused(val reason: Refusal) : Result
  }

  fun resolve(raw: String): Result {
    val path = try { Path.of(raw) } catch (e: InvalidPathException) { return Result.Refused(Refusal.INVALID) }
    if (!path.isAbsolute) return Result.Refused(Refusal.NOT_ABSOLUTE)
    val normalized = path.normalize()
    val canonical = physical(normalized) ?: return Result.Refused(Refusal.UNRESOLVABLE)
    return Result.Resolved(AgentPath(normalized, canonical))
  }

  /**
   * The physical location of an absolute, lexically normalized path, or null when an existing entry
   * on the way cannot be followed.
   *
   * The walk asks with NOFOLLOW_LINKS on purpose: a dangling link EXISTS as an entry. Asked the
   * following way, it would look absent, its name would be appended as a new file inside the project,
   * and the write would then create whatever the link points at — anywhere on the disk.
   */
  fun physical(normalized: Path): Path? {
    var existing: Path? = normalized
    val tail = ArrayDeque<Path>()
    while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
      existing.fileName?.let { tail.addFirst(it) }
      existing = existing.parent
    }
    // Nothing on the way exists, not even the root (an unmounted drive): the lexical form is all
    // there is, and the policy compares it like any other text.
    val base = existing ?: return normalized
    val real = try { base.toRealPath() } catch (e: IOException) { return null }
    return tail.fold(real) { resolved, name -> resolved.resolve(name.toString()) }
  }
}
