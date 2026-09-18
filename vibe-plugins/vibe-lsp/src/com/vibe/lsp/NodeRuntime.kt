// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import java.io.File
import java.nio.file.Path

/**
 * The Node interpreter of this machine, as the running IDE sees it — [NodeInterpreter] with the real
 * file system, the real login-shell PATH and the real setting attached.
 *
 * Kept apart from the search itself so the search stays pure. Two things happen only here:
 *
 * **The shell's PATH.** A GUI application on macOS is started by launchd with a PATH of four system
 * folders, which is why a Node installed by any version manager was invisible. The platform already
 * solves this for everyone — `EnvironmentUtil` runs the login shell once and remembers what it
 * printed — and this is the standard answer rather than our own guess.
 *
 * **The child's PATH.** Handing the server an absolute `node` is not enough: the ESLint server calls
 * `node` and `npx` by name from inside itself. So the interpreter's own folder is prepended to the
 * PATH the server process is started with.
 */
object NodeRuntime {
  /** What the search found, with the real machine underneath. */
  fun outcome(projectBase: String?): NodeInterpreter.Outcome = NodeInterpreter.detect(
    probe = NodeInterpreter.RealProbe,
    home = Path.of(System.getProperty("user.home").orEmpty()),
    projectBase = projectBase?.let { Path.of(it) },
    shellPath = shellPath(),
    windows = com.vibe.agent.util.ExecutableNames.isWindows(),
    configured = NodeInterpreter.stored(),
  )

  /** The interpreter to run, or null when there is none — the caller decides what to say. */
  fun path(projectBase: String?): String? =
    (outcome(projectBase) as? NodeInterpreter.Outcome.Found)?.path

  /**
   * The command for a Node script, and the bare name when no interpreter was found.
   *
   * The bare name is deliberate: the start then fails where it would have failed anyway, and the
   * notice that names the setting is shown by [VibeLspMissingServerNotifier].
   */
  fun command(projectBase: String?, vararg args: String): List<String> =
    listOf(path(projectBase) ?: NodeInterpreter.binaryName(com.vibe.agent.util.ExecutableNames.isWindows())) + args

  /**
   * Environment for a server process: the interpreter's folder first in PATH.
   *
   * Empty when no interpreter was found — an empty map leaves the child with the IDE's own
   * environment, which is exactly what it had before.
   */
  fun childEnvironment(projectBase: String?): Map<String, String> {
    val node = path(projectBase) ?: return emptyMap()
    val bin = Path.of(node).parent?.toString() ?: return emptyMap()
    val inherited = shellPath().joinToString(File.pathSeparator)
    val existing = System.getenv("PATH").orEmpty()
    val parts = listOf(bin, inherited, existing).filter { it.isNotBlank() }
    return mapOf("PATH" to parts.joinToString(File.pathSeparator))
  }

  /** PATH of the login shell, the way the platform reads it; the IDE's own PATH is the fallback. */
  fun shellPath(): List<Path> {
    val raw = runCatching { com.intellij.util.EnvironmentUtil.getEnvironmentMap()["PATH"] }.getOrNull()
                ?: System.getenv("PATH")
    return raw.orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }.map { Path.of(it) }
  }
}
