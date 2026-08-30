// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.git

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.util.ProcessSupport
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the three git commands the report is built from — and nothing else.
 *
 * `git` is invoked as an argv list, never through a shell: the project path is data, and a shell
 * would make a folder named `; rm -rf ~` into a command. The three calls are read-only by
 * construction, which is what makes this safe to run without asking the user each time.
 */
@Service(Service.Level.PROJECT)
class GitStateService(private val project: Project) {
  /** Enough to answer «что изменилось» without turning into a wall of output. */
  fun collect(logLimit: Int = DEFAULT_LOG_LIMIT): Result<RepoState.State> = runCatching {
    val base = project.basePath ?: error(t("git.noProject"))
    val dir = File(base)
    check(File(dir, ".git").exists()) { NOT_A_REPO }
    val status = run(dir, listOf("git", "status", "--porcelain", "-b"))
    val numstat = run(dir, listOf("git", "diff", "--numstat", "HEAD"))
    val log = run(dir, listOf("git", "log", "--oneline", "-n", logLimit.toString()))
    RepoState.assemble(status, numstat, log)
  }

  private fun run(dir: File, command: List<String>): String {
    val process = ProcessBuilder(command).directory(dir).redirectErrorStream(false).start()
    val out = ProcessSupport.drain(process.inputStream, "vibe-git-out")
    val err = ProcessSupport.drain(process.errorStream, "vibe-git-err")
    if (!process.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      error(t("git.timeout", "seconds" to TIMEOUT_SEC, "command" to command.joinToString(" ")))
    }
    // A non-zero exit with an empty stdout is a real failure; `git diff` of a fresh repo is not.
    val text = out.get(ProcessSupport.DRAIN_JOIN_TIMEOUT_SEC, TimeUnit.SECONDS).orEmpty()
    if (process.exitValue() != 0 && text.isBlank()) {
      val reason = err.get(ProcessSupport.DRAIN_JOIN_TIMEOUT_SEC, TimeUnit.SECONDS).orEmpty().trim().takeLast(200)
      if (reason.isNotEmpty()) error(reason)
    }
    return text
  }

  companion object {
    const val DEFAULT_LOG_LIMIT = 5
    const val NOT_A_REPO = "not-a-repo"
    private const val TIMEOUT_SEC = 15L

    fun getInstance(project: Project): GitStateService = project.getService(GitStateService::class.java)
  }
}
