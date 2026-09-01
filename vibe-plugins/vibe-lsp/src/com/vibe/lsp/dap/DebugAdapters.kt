// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.dap

import com.vibe.lsp.ServerBinaries
import java.nio.file.Files
import java.nio.file.Path

/**
 * Everything about a debug adapter that can be decided without a running IDE.
 *
 * The point of the split is that starting a debugger is the one feature nobody tests by hand
 * twice: it either comes up on the first breakpoint or the person goes back to var_dump. So the
 * decisions — which adapter owns this file, where it is installed, what command starts it, what
 * launch configuration to write — live here as pure functions over a filesystem probe, and the
 * LSP4IJ factories stay thin wrappers that cannot hide a decision from a test.
 *
 * The commands are not invented: they are taken from the adapters' own published entry points
 * (vscode-js-debug ships `dapDebugServer.js`, vscode-php-debug ships `out/phpDebug.js`), which is
 * also what LSP4IJ's own templates use.
 */
object DebugAdapters {

  /** How the adapter talks to us once started. */
  enum class Transport {
    /** The adapter opens a TCP port we pass on the command line and prints a ready line. */
    SOCKET,

    /** The adapter speaks DAP over its own stdin/stdout — nothing to wait for. */
    STDIO,
  }

  data class AdapterSpec(
    val id: String,
    val displayName: String,
    /** File extensions this adapter debugs — lowercase, without the dot. */
    val extensions: Set<String>,
    /** Path parts of the entry point inside the unpacked archive. */
    val entry: List<String>,
    val transport: Transport,
    /** Prefix of the line the adapter prints once its socket is up; null for stdio adapters. */
    val readyPattern: String?,
    /** Where the archive comes from — shown to a person who has to install it by hand. */
    val downloadUrl: String,
  )

  /**
   * vscode-js-debug: the debugger behind VS Code's JavaScript support, run in its standalone
   * DAP-server mode. The port is an argument rather than a negotiated value, so the command
   * carries LSP4IJ's `${port}` placeholder verbatim.
   */
  val JS_DEBUG = AdapterSpec(
    id = "vibeJsDebug",
    displayName = "JavaScript/TypeScript (vscode-js-debug)",
    extensions = setOf("ts", "tsx", "mts", "cts", "js", "jsx", "mjs", "cjs"),
    entry = listOf("js-debug", "src", "dapDebugServer.js"),
    transport = Transport.SOCKET,
    readyPattern = "Debug server listening at ",
    downloadUrl = "https://github.com/microsoft/vscode-js-debug/releases/latest",
  )

  /**
   * vscode-php-debug: the Xdebug client. It is a listener, not a launcher — PHP connects to it —
   * which is why the default configuration says "wait for Xdebug on 9003" instead of "run this
   * file": a web request from the browser is how PHP is debugged nine times out of ten.
   */
  val PHP_DEBUG = AdapterSpec(
    id = "vibePhpDebug",
    displayName = "PHP (Xdebug)",
    extensions = setOf("php"),
    entry = listOf("extension", "out", "phpDebug.js"),
    transport = Transport.STDIO,
    readyPattern = null,
    downloadUrl = "https://github.com/xdebug/vscode-php-debug/releases/latest",
  )

  val ALL: List<AdapterSpec> = listOf(JS_DEBUG, PHP_DEBUG)

  /** The default Xdebug port since Xdebug 3. Named because it appears in two places. */
  const val XDEBUG_PORT = 9003

  /** The adapter responsible for a file, or null when the file is none of our business. */
  fun adapterFor(fileName: String, specs: List<AdapterSpec> = ALL): AdapterSpec? {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    if (extension.isEmpty()) return null
    return specs.firstOrNull { extension in it.extensions }
  }

  /**
   * Directories an adapter may live in, most-preferred first.
   *
   * LSP4IJ's own directory comes first for the same reason a person's own language server wins
   * over ours: if they already installed the adapter through the LSP4IJ dialog, that copy is the
   * one they will keep updating, and quietly running a second one would make version questions
   * unanswerable.
   */
  fun candidateDirs(spec: AdapterSpec, home: String? = System.getProperty("user.home")): List<Path> =
    buildList {
      home?.let {
        add(Path.of(it, ".lsp4ij", "dap", spec.id))
        add(Path.of(it, ".local", "share", "vibe", "dap", spec.id))
      }
    }

  /**
   * The entry-point script of an installed adapter, or null when it is nowhere to be found.
   *
   * [exists] is injected so the resolution is testable on a machine that has neither adapter —
   * which is every machine until someone installs one.
   */
  fun entryPoint(
    spec: AdapterSpec,
    dirs: List<Path> = candidateDirs(spec),
    exists: (Path) -> Boolean = { Files.isRegularFile(it) },
  ): Path? = dirs.asSequence()
    .map { dir -> spec.entry.fold(dir) { acc, part -> acc.resolve(part) } }
    .firstOrNull(exists)

  /**
   * The command line that starts the adapter, or null when it is not installed.
   *
   * Null rather than a guess: a command built from a path that does not exist starts nothing and
   * fails with "Cannot run program", which names the symptom and hides the cause. The caller turns
   * the null into a sentence that says what to install.
   */
  fun command(
    spec: AdapterSpec,
    entryPoint: Path? = entryPoint(spec),
    node: String = ServerBinaries.resolve("node"),
  ): String? {
    val script = entryPoint ?: return null
    return when (spec.transport) {
      // `${port}` is LSP4IJ's placeholder: it picks a free port and substitutes it at start.
      Transport.SOCKET -> "$node $script \${port} 127.0.0.1"
      Transport.STDIO -> "$node $script"
    }
  }

  /**
   * The launch configuration written into a fresh run configuration.
   *
   * This is the whole point of the feature: LSP4IJ can debug anything, but only after a person
   * hand-writes this JSON, and nobody knows by heart that vscode-js-debug wants `pwa-node` or that
   * the PHP adapter treats "listen for Xdebug" as a launch request.
   */
  fun launchConfiguration(spec: AdapterSpec, filePath: String, workspaceFolder: String): String =
    when (spec.id) {
      JS_DEBUG.id -> """
        {
          "type": "pwa-node",
          "name": "${'$'}{file}",
          "request": "launch",
          "program": "$filePath",
          "cwd": "$workspaceFolder",
          "sourceMaps": true,
          "outFiles": ["$workspaceFolder/**/*.(m|c|)js", "!**/node_modules/**"],
          "__workspaceFolder": "$workspaceFolder"
        }
      """.trimIndent()

      // Not "run this file": PHP is debugged through a request to the web server, and a
      // configuration that runs the script under the CLI interpreter would break on the first
      // page that needs a session.
      else -> """
        {
          "type": "php",
          "name": "Listen for Xdebug",
          "request": "launch",
          "port": $XDEBUG_PORT,
          "cwd": "$workspaceFolder"
        }
      """.trimIndent()
    }

  /** The attach configuration — the same JSON minus the part that starts anything. */
  fun attachConfiguration(spec: AdapterSpec, workspaceFolder: String, port: Int): String =
    when (spec.id) {
      JS_DEBUG.id -> """
        {
          "type": "pwa-node",
          "name": "Attach to process",
          "request": "attach",
          "port": $port,
          "sourceMaps": true,
          "outFiles": ["$workspaceFolder/**/*.(m|c|)js", "!**/node_modules/**"],
          "__workspaceFolder": "$workspaceFolder"
        }
      """.trimIndent()

      else -> """
        {
          "type": "php",
          "name": "Attach to Xdebug",
          "request": "attach",
          "port": $port,
          "cwd": "$workspaceFolder"
        }
      """.trimIndent()
    }

  /** The port a fresh attach configuration should offer. */
  fun defaultAttachPort(spec: AdapterSpec): Int = when (spec.id) {
    // The Node inspector's own default; vscode-js-debug's template uses it too.
    JS_DEBUG.id -> 5858
    else -> XDEBUG_PORT
  }
}
