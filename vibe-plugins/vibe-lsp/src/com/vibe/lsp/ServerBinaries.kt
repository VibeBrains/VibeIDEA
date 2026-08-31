// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import java.nio.file.Files
import java.nio.file.Path

/**
 * Pure helpers resolving language-server executables.
 * GUI apps on macOS do not inherit the shell PATH, so well-known install
 * locations are probed explicitly in addition to PATH (VibeIDE lesson).
 */
internal object ServerBinaries {
  private val EXTRA_DIRS: List<Path> = buildList {
    System.getProperty("user.home")?.let {
      add(Path.of(it, ".local", "bin"))
      add(Path.of(it, ".npm-global", "bin"))
      add(Path.of(it, ".composer", "vendor", "bin"))
      add(Path.of(it, ".config", "composer", "vendor", "bin"))
    }
    add(Path.of("/opt/homebrew/bin"))
    add(Path.of("/usr/local/bin"))
  }

  /**
   * The executable, or null when it is nowhere to be found. The doctor needs the honest
   * answer: a path invented from the bare name would report an installed server that then
   * fails to start, which is exactly the silence the doctor exists to remove.
   */
  fun find(binary: String): String? {
    val fromPath = System.getenv("PATH")?.split(java.io.File.pathSeparator).orEmpty()
      .asSequence().map { Path.of(it, binary) }.firstOrNull { Files.isExecutable(it) }
    if (fromPath != null) return fromPath.toString()
    return EXTRA_DIRS.asSequence().map { it.resolve(binary) }.firstOrNull { Files.isExecutable(it) }?.toString()
  }

  // Falls back to the bare name: the failure to start then names exactly what is missing.
  internal fun resolve(binary: String): String = find(binary) ?: binary

  fun vtslsCommand(): List<String> = nodeServerCommand("vtsls", "--stdio")

  /**
   * The Phpactor phar we ship, or null when running from sources without it.
   *
   * `PathManager.getPluginsPath()` rather than a path relative to the jar: the plugin is a jar
   * inside the distribution, and asking the platform where plugins live is the only spelling that
   * works both in the installer and in a dev run.
   */
  fun bundledPhpactor(): String? = bundled("phpactor.phar")

  /** A file inside the servers directory we ship, or null when running from sources without it. */
  private fun bundled(vararg parts: String): String? {
    val path = java.nio.file.Path.of(
      com.intellij.openapi.application.PathManager.getPluginsPath(), "vibe-lsp", "servers", *parts)
    return path.takeIf { Files.isRegularFile(it) }?.toString()
  }

  /** Entry points of the Node servers we ship, by binary name. */
  private val BUNDLED_NODE_ENTRY = mapOf(
    "vtsls" to arrayOf("node", "node_modules", "@vtsls", "language-server", "bin", "vtsls.js"),
    "vscode-css-language-server" to arrayOf("node", "node_modules", "vscode-langservers-extracted", "bin", "vscode-css-language-server"),
    "vscode-eslint-language-server" to arrayOf("node", "node_modules", "vscode-langservers-extracted", "bin", "vscode-eslint-language-server"),
  )

  fun bundledNode(binary: String): String? = BUNDLED_NODE_ENTRY[binary]?.let { bundled(*it) }

  /**
   * How to start a Node server: the person's own installation first, then ours on the machine's Node.
   *
   * Their own wins for the same reason as with Phpactor — a project may be pinned to another
   * version, and our copy ages with the IDE release rather than with their decisions. Without Node
   * on the machine the bare name is returned and the start fails loudly, which is the honest end:
   * a JavaScript server cannot run without a JavaScript runtime, and pretending otherwise would
   * produce silence instead of a sentence.
   */
  private fun nodeServerCommand(binary: String, vararg args: String): List<String> {
    find(binary)?.let { return listOf(it) + args }
    bundledNode(binary)?.let { entry -> return listOf(resolve("node"), entry) + args }
    return listOf(binary) + args
  }

  /**
   * How to start Phpactor.
   *
   * The user's OWN installation wins over the bundled phar — always. A project pinned to another
   * version must not break against ours, and the bundled copy ages with the IDE release while
   * theirs ages with their decisions.
   *
   * The phar needs an interpreter: `php <phar> language-server`. A machine without PHP gets the
   * bare name and an honest failure to start rather than a mysterious silence.
   */
  fun phpactorCommand(): List<String> {
    find("phpactor")?.let { return listOf(it, "language-server") }
    bundledPhpactor()?.let { phar -> return listOf(resolve("php"), phar, "language-server") }
    return listOf("phpactor", "language-server")
  }

  fun cssCommand(): List<String> = nodeServerCommand("vscode-css-language-server", "--stdio")

  fun eslintCommand(): List<String> = nodeServerCommand("vscode-eslint-language-server", "--stdio")
}
