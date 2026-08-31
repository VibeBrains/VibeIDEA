// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

/**
 * Answers the one question the language support cannot answer by itself: is the server
 * that does the work actually here?
 *
 * Phpactor we now SHIP: one 4.3 MB phar (MIT) that runs on the machine's PHP, so a fresh install
 * has working PHP navigation for anyone who writes PHP. The npm servers stay outside — bundling
 * them means bundling Node, which is a second runtime to keep patched.
 *
 * The price of what is still outside is that a fresh install looks broken — "go to definition"
 * does nothing at all — and silence is the worst possible answer, because it names neither the
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

  /** Where the server came from — the person needs to know whose version is running. */
  enum class Source { OWN, BUNDLED, ABSENT }

  data class Check(val spec: ServerSpec, val path: String?, val source: Source = if (path != null) Source.OWN else Source.ABSENT) {
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
    // NOT `brew install phpactor`: there is no such formula, and a command that fails is worse
    // than no command — the person concludes the whole feature is broken. The phar from the
    // project's own releases lands in ~/.local/bin, which is searched by ServerBinaries and needs
    // no sudo.
    installCommand = "mkdir -p ~/.local/bin && curl -Lo ~/.local/bin/phpactor " +
                     "https://github.com/phpactor/phpactor/releases/latest/download/phpactor.phar && " +
                     "chmod +x ~/.local/bin/phpactor",
    extensions = setOf("php"),
  )

  /**
   * CSS/SCSS/LESS — the gap Community leaves open. HTML and JSON come from the same npm package
   * and are deliberately not wired: the platform serves them itself.
   */
  val CSS = ServerSpec(
    id = "vibeCss",
    displayName = "CSS/SCSS/LESS (vscode-css-language-server)",
    binary = "vscode-css-language-server",
    installCommand = "npm install -g vscode-langservers-extracted",
    extensions = setOf("css", "scss", "less"),
  )

  /** ESLint: the project's own rules in the editor. Silent in a project that has no ESLint config. */
  val ESLINT = ServerSpec(
    id = "vibeEslint",
    displayName = "ESLint (vscode-eslint-language-server)",
    binary = "vscode-eslint-language-server",
    installCommand = "npm install -g vscode-langservers-extracted",
    extensions = setOf("eslintrc"),
  )

  /**
   * Debug adapters. Same shape as the language servers and the same reason: they are npm and
   * composer packages, and bundling them would mean shipping someone else's runtime.
   *
   * They are checked separately from the servers because their absence breaks a different thing —
   * breakpoints rather than navigation — and a report that mixes the two sends people to fix the
   * wrong package.
   */
  val JS_DEBUG = ServerSpec(
    id = "vibeJsDebug",
    displayName = "JavaScript/TypeScript (vscode-js-debug)",
    binary = "js-debug-adapter",
    installCommand = "npm install -g js-debug-adapter",
    extensions = setOf("ts", "tsx", "js", "jsx", "mjs", "cjs"),
  )

  val PHP_DEBUG = ServerSpec(
    id = "vibePhpDebug",
    displayName = "PHP (Xdebug)",
    binary = "php-debug-adapter",
    installCommand = "composer global require xdebug/vscode-php-debug",
    extensions = setOf("php"),
  )

  val ALL: List<ServerSpec> = listOf(VTSLS, PHPACTOR, CSS, ESLINT)

  /** Checked on request rather than on every file open: debugging is a deliberate act. */
  val DEBUG_ADAPTERS: List<ServerSpec> = listOf(JS_DEBUG, PHP_DEBUG)

  /** [resolve] returns the executable path, or null when the binary is nowhere to be found. */
  fun check(
    specs: List<ServerSpec> = ALL,
    resolve: (String) -> String? = ServerBinaries::find,
    bundled: (ServerSpec) -> String? = ::bundledPath,
  ): List<Check> = specs.map { spec ->
    // The user's own installation first — always. It is theirs, it may be pinned by the project,
    // and ours ages with the IDE release rather than with their decisions.
    val own = resolve(spec.binary)
    if (own != null) return@map Check(spec, own, Source.OWN)
    val shipped = bundled(spec)
    if (shipped != null) Check(spec, shipped, Source.BUNDLED) else Check(spec, null, Source.ABSENT)
  }

  /** What we ship ourselves. Only Phpactor today; the npm servers would drag Node along. */
  fun bundledPath(spec: ServerSpec): String? = when (spec.id) {
    PHPACTOR.id -> ServerBinaries.bundledPhpactor()
    else -> null
  }

  /** The server responsible for a file, or null when the file is none of our business. */
  fun serverFor(fileName: String, specs: List<ServerSpec> = ALL): ServerSpec? {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    if (extension.isEmpty()) return null
    return specs.firstOrNull { extension in it.extensions }
  }
}
