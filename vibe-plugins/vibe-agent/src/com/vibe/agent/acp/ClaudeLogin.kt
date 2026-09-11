// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import java.io.File
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Is Claude Code logged in — asked the way its documentation answers it: `claude auth status`, exit
 * code 0 for yes and 1 for no (code.claude.com/docs/en/cli-reference, checked 11.09.2026).
 *
 * Only the exit code is read. The output names the account — e-mail and organisation — and a
 * diagnostic that copies it into a report meant to be pasted somewhere is a leak with good
 * intentions. The credentials themselves are never touched: decision №65 and the Legal page forbid
 * storing or relaying them, and asking the program that owns them is the permitted way.
 */
object ClaudeLogin {
  enum class State { LOGGED_IN, LOGGED_OUT, UNKNOWN, NOT_INSTALLED }

  const val CLI = "claude"
  val COMMAND: List<String> = listOf(CLI, "auth", "status")

  /** A status query answers at once; one that takes longer is itself the finding. */
  val TIMEOUT: Duration = Duration.ofSeconds(10)

  private const val EXIT_LOGGED_IN = 0
  private const val EXIT_LOGGED_OUT = 1

  /**
   * @param onPath whether the command can be found — the same search the agent launch uses.
   * @param run runs [COMMAND] and returns its exit code, or null when it did not finish in time.
   */
  fun check(onPath: (String) -> Boolean, run: (List<String>) -> Int?): State {
    if (!onPath(CLI)) return State.NOT_INSTALLED
    return when (run(COMMAND)) {
      EXIT_LOGGED_IN -> State.LOGGED_IN
      EXIT_LOGGED_OUT -> State.LOGGED_OUT
      else -> State.UNKNOWN
    }
  }

  /**
   * The production runner: output discarded unread, bounded by [TIMEOUT].
   *
   * PATH gets the directories of the resolved `claude` and `node`: a GUI app on macOS does not
   * inherit the shell's PATH, and an npm-installed `claude` starts with `#!/usr/bin/env node`.
   */
  fun exitCode(command: List<String>): Int? {
    val binary = AcpClient.resolveBinary(command.first())
    val builder = ProcessBuilder(listOf(binary) + command.drop(1))
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .redirectError(ProcessBuilder.Redirect.DISCARD)
    val extra = listOf(binary, AcpClient.resolveBinary("node"))
      .map { File(it) }.filter { it.isAbsolute }.mapNotNull { it.parent }.distinct()
    builder.environment()["PATH"] = (listOf(builder.environment()["PATH"].orEmpty()) + extra)
      .filter { it.isNotEmpty() }.joinToString(File.pathSeparator)
    val process = try { builder.start() } catch (e: IOException) { return null }
    if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
      process.destroyForcibly()
      return null
    }
    return process.exitValue()
  }
}
