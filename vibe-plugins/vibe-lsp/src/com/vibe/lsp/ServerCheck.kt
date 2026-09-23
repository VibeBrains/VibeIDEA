// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import java.util.concurrent.TimeUnit

/**
 * "Check" for a language server: start it and see what it answers.
 *
 * Why start it rather than look at the file: an executable of the wrong architecture, a dangling symlink and a
 * half-installed package all look perfectly healthy on disk. The same reasoning stands behind the Node "Check" button.
 *
 * A twist Node does not have: **a language server started without arguments does not answer, it WAITS** — it speaks
 * over stdio and stays silent until it receives `initialize`. Hence the version flag and a timeout: silence here is
 * not a failure but the normal behaviour of a program asked in a language it does not speak.
 */
object ServerCheck {
  /** How long to wait for an answer: a Node-based server does not start instantly, and forever is too long. */
  const val TIMEOUT_SECONDS = 10L

  /** What the check found. The settings page builds the message; this is only the facts. */
  sealed interface Outcome {
    /** The server started and reported its version. */
    data class Works(val path: String, val version: String) : Outcome

    /**
     * The server was found and started but reported no version.
     *
     * This is NOT a failure: some servers do not know `--version` and simply wait for the protocol. The file is there
     * and runs — enough for the settings page, and calling it broken would be false.
     */
    data class NoVersion(val path: String) : Outcome

    /** No file, neither in the setting nor anywhere the search looks. */
    data object Missing : Outcome

    /** Could not start: wrong architecture, no permission, a dangling symlink. */
    data class Failed(val path: String, val reason: String) : Outcome
  }

  /**
   * Pick the version out of the output: servers print it differently.
   *
   * `vtsls` answers a bare `0.2.9`, `phpactor` a line `Phpactor 2026.06.23.0`, some packages add a name and a path. The
   * first non-empty line is taken and the number cut out of it when present: a bare number is more useful to show, but
   * the whole line beats nothing.
   */
  fun versionFrom(output: String): String? {
    val line = output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
    val number = Regex("""\d+(\.\d+)+""").find(line)?.value
    return number ?: line
  }

  /**
   * Start [path] and ask for its version.
   *
   * @param run a replacement for the launch in tests, where a real process is not needed
   */
  fun of(
    path: String?,
    run: (String) -> ProcessResult = ::runVersion,
  ): Outcome {
    if (path.isNullOrBlank()) return Outcome.Missing
    val result = runCatching { run(path) }
      .getOrElse { return Outcome.Failed(path, it.message.orEmpty()) }
    return when {
      result.timedOut -> Outcome.NoVersion(path)
      result.exitCode == 0 -> versionFrom(result.output)?.let { Outcome.Works(path, it) } ?: Outcome.NoVersion(path)
      // A non-zero code with readable text is a failure; without text it is again "does not know the flag".
      else -> versionFrom(result.output)?.let { Outcome.Failed(path, it) } ?: Outcome.NoVersion(path)
    }
  }

  /** How the launch ended: exit code, output, and whether the wait timed out. */
  data class ProcessResult(val exitCode: Int, val output: String, val timedOut: Boolean)

  /**
   * Run the server the way the IDE launches it: the same command rule ([ServerBinaries.runCommand]) and the same
   * environment, with the IDE's Node first in PATH.
   *
   * Otherwise the check tests something else. An npm-installed server is a shim starting with `#!/usr/bin/env node`;
   * a GUI application's PATH has no version-managed Node in it, so the shim fails with "env: node: No such file" —
   * and a perfectly working server gets reported as broken.
   */
  private fun runVersion(path: String): ProcessResult {
    val builder = ProcessBuilder(ServerBinaries.runCommand(path, "--version")).redirectErrorStream(true)
    builder.environment().putAll(NodeRuntime.childEnvironment(null))
    val process = builder.start()
    if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      return ProcessResult(exitCode = -1, output = "", timedOut = true)
    }
    return ProcessResult(process.exitValue(), process.inputStream.readBytes().decodeToString(), timedOut = false)
  }
}
