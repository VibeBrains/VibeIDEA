// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

/**
 * How to run a server's `npm install -g …`: with the npm of the Node interpreter the IDE uses.
 *
 * Running the command through a login shell finds whatever `npm` the shell resolves first — often a different Node than
 * the one chosen on the settings page (a version manager's default, a Homebrew install). The package then lands in
 * another Node's global folder: the IDE never sees it and keeps running its bundled copy, so "Install latest" installs
 * something that is never used.
 *
 * So an npm command is run with the npm that sits next to the IDE's Node, and nothing else. When there is no Node, the
 * answer says so instead of running a command that would fail with a less useful message.
 */
object NpmInstall {
  sealed interface Plan {
    /** Not an npm command (a download, for instance): run it as written. */
    data object NotNpm : Plan

    /** An npm command, but the IDE has no Node interpreter to run it with. */
    data object NoNode : Plan

    /** The exact command line to run. */
    data class Run(val argv: List<String>) : Plan
  }

  /**
   * Plan the install.
   *
   * @param command the catalogue's install command, e.g. `npm install -g @vtsls/language-server`
   * @param node the IDE's Node interpreter, or null when none was found
   */
  fun plan(command: String, node: String?, windows: Boolean): Plan {
    val words = command.trim().split(Regex("\\s+"))
    if (words.firstOrNull() != NPM) return Plan.NotNpm
    val interpreter = node ?: return Plan.NoNode
    // The folder is cut by string, on either separator, rather than through `Path`: the plan is decided for a target OS
    // that need not be the one this code runs on, and `Path` would read `C:\...` as one file name on macOS.
    val cut = maxOf(interpreter.lastIndexOf('/'), interpreter.lastIndexOf('\\'))
    if (cut <= 0) return Plan.NoNode
    val npm = interpreter.substring(0, cut + 1) + if (windows) "$NPM.cmd" else NPM
    return Plan.Run(listOf(npm) + words.drop(1))
  }

  private const val NPM = "npm"
}
