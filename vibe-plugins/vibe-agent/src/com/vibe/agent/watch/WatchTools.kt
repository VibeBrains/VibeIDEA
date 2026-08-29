// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.watch

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Finds the external tools the pipeline runs on, and says what to install when they are missing.
 *
 * The binaries are NOT bundled and NOT downloaded: shipping GPL builds raises signing and licence
 * questions, and a private mirror needs release infrastructure this fork does not have yet. So the
 * feature uses what the machine has — and when it has nothing, it says exactly which one line to
 * run. A feature that silently does nothing is worse than one that asks for a dependency.
 */
object WatchTools {
  data class Tools(val ytDlp: String, val ffmpeg: String, val ffprobe: String)

  /** GUI apps on macOS do not inherit the shell PATH — the same trap as with ACP agents. */
  private val EXTRA_DIRS = listOf(
    "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin",
    System.getProperty("user.home") + "/.local/bin",
  )

  fun find(binary: String): String? {
    val fromPath = System.getenv("PATH")?.split(File.pathSeparator).orEmpty()
    return (fromPath + EXTRA_DIRS).asSequence()
      .map { Path.of(it, binary) }
      .firstOrNull { Files.isExecutable(it) }
      ?.toString()
  }

  /** Either the tools, or a message naming what is missing and how to get it. */
  fun resolve(): Result<Tools> {
    val ytDlp = find("yt-dlp")
    val ffmpeg = find("ffmpeg")
    val ffprobe = find("ffprobe") ?: ffmpeg?.replace("ffmpeg", "ffprobe")?.takeIf { Files.isExecutable(Path.of(it)) }
    val missing = buildList {
      if (ytDlp == null) add("yt-dlp")
      if (ffmpeg == null) add("ffmpeg")
    }
    if (missing.isNotEmpty()) {
      return Result.failure(IllegalStateException(com.vibe.agent.i18n.VibeI18n.t(
        "watch.toolsMissing",
        "tools" to missing.joinToString(", "),
        "install" to missing.joinToString(" "),
      )))
    }
    return Result.success(Tools(ytDlp!!, ffmpeg!!, ffprobe ?: ffmpeg))
  }
}
