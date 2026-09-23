// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

/**
 * Which servers to touch after a branch switch — a pure decision, apart from the work itself.
 *
 * A language server keeps ITS OWN model of the project, built at start: parsed files, dependencies, configuration.
 * `git checkout` changes the files under it massively and silently, and the server keeps answering for the old
 * world — completions from another branch, errors on lines that no longer exist.
 *
 * A restart is not free, though: on a large project it means tens of seconds without completion or navigation. So
 * **only running** servers are touched: a server that is not running remembers nothing and builds its model on its
 * own when needed.
 */
object BranchSwitchRefresh {
  /** Our servers, by the exact ids they are registered under in LSP4IJ. */
  val SERVER_IDS: List<String> = listOf(
    "vibeVtsls", "vibeAngular", "vibePhp", "vibeCss", "vibeStylus", "vibeSomeSass",
    "vibeVue", "vibeSvelte", "vibeAstro", "vibeTailwind", "vibeEslint",
  )

  /**
   * Which servers to restart.
   *
   * @param running which of our servers are running now
   */
  fun toRestart(running: Set<String>): List<String> = SERVER_IDS.filter { it in running }
}
