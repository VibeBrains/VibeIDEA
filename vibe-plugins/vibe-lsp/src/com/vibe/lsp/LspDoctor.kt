// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

/**
 * Answers the one question the language support cannot answer by itself: is the server
 * that does the work actually here?
 *
 * We ship the CLIENT, not the servers: vtsls comes from npm and Phpactor from composer or
 * Homebrew, and bundling either would mean shipping a runtime and taking on its licence.
 * The price of that decision is that a fresh install looks broken — "go to definition" does
 * nothing at all — and silence is the worst possible answer, because it names neither the
 * cause nor the fix.
 *
 * The check is a pure function of the resolver, so the report is testable without a machine
 * that happens to have (or not have) the binaries installed.
 */
object LspDoctor {
  /** One language server as the user meets it: a name, a binary and a way to get it. */
  data class ServerSpec(
    val id: String,
    val displayName: String,
    val binary: String,
    /** Copy-pasteable, one line, no sudo — the answer to "so what do I do now?". */
    val installCommand: String,
    /** Extensions this server is mapped onto — used to ask about it only when it is relevant. */
    val extensions: Set<String>,
  )

  data class Check(val spec: ServerSpec, val path: String?) {
    val installed: Boolean get() = path != null
  }

  val VTSLS = ServerSpec(
    id = "vibeVtsls",
    displayName = "TypeScript (vtsls)",
    binary = "vtsls",
    installCommand = "npm install -g @vtsls/language-server",
    extensions = setOf("ts", "tsx", "mts", "cts", "js", "jsx", "mjs", "cjs"),
  )

  val PHPACTOR = ServerSpec(
    id = "vibePhpactor",
    displayName = "PHP (Phpactor)",
    binary = "phpactor",
    installCommand = "brew install phpactor",
    extensions = setOf("php"),
  )

  val ALL: List<ServerSpec> = listOf(VTSLS, PHPACTOR)

  /** [resolve] returns the executable path, or null when the binary is nowhere to be found. */
  fun check(specs: List<ServerSpec> = ALL, resolve: (String) -> String? = ServerBinaries::find): List<Check> =
    specs.map { Check(it, resolve(it.binary)) }

  /** The server responsible for a file, or null when the file is none of our business. */
  fun serverFor(fileName: String, specs: List<ServerSpec> = ALL): ServerSpec? {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    if (extension.isEmpty()) return null
    return specs.firstOrNull { extension in it.extensions }
  }
}
