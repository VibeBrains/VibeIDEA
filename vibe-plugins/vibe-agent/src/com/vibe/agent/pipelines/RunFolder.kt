// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

import com.vibe.agent.defaults.VibeLocal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Where one pipeline run keeps what outlives its feed: the brief it was launched with, the seams the steps agreed on,
 * and the handoffs written when a step hit its ceiling.
 *
 * In `.vibe/local/runs/<run>/`, not in the repository: these are our working notes, and a run must not add files to
 * someone's project (the same reason `.autopilot/` in the repository was not taken from Autopilot, 17.09.2026).
 *
 * Every write is best effort: a run must not die because a note could not be saved.
 */
object RunFolder {
  const val BRIEF = "brief.md"
  const val INTERFACES = "interfaces.md"

  fun dir(projectBase: String?, runId: String): Path? =
    projectBase?.let { VibeLocal.dir(it).resolve("runs").resolve(safe(runId)) }

  fun write(projectBase: String?, runId: String, name: String, text: String): Path? {
    val dir = dir(projectBase, runId) ?: return null
    return runCatching {
      Files.createDirectories(dir)
      val file = dir.resolve(name)
      Files.writeString(file, text)
      file
    }.getOrNull()
  }

  /** Appends a dated block — this is how the brief grows: what was said stays, what is new goes under it. */
  fun append(projectBase: String?, runId: String, name: String, text: String): Path? {
    val dir = dir(projectBase, runId) ?: return null
    return runCatching {
      Files.createDirectories(dir)
      val file = dir.resolve(name)
      Files.writeString(file, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
      file
    }.getOrNull()
  }

  fun read(projectBase: String?, runId: String, name: String): String? =
    dir(projectBase, runId)?.resolve(name)?.let { file -> runCatching { Files.readString(file) }.getOrNull() }

  /** A run id comes from our own ledger, but it names a directory: anything but a plain name is refused. */
  internal fun safe(runId: String): String = runId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
    .ifEmpty { "run" }
}
