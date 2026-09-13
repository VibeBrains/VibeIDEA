// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.ide.util.PropertiesComponent
import java.nio.file.Files
import java.nio.file.Path

/**
 * Which TypeScript language server serves `*.ts`/`*.tsx`/`*.js`.
 *
 * TypeScript 7 is the compiler rewritten in Go, and it speaks LSP itself: `tsc --lsp --stdio`
 * (TypeScript 7.0 and newer; the shape the nvim-lspconfig `tsc` config uses, checked 13.09.2026).
 * It loads a large project several times faster than the JavaScript server vtsls wraps. Its native
 * binary ships inside the project's own `typescript` package, one platform package per OS — so a
 * project that is on TypeScript 7 already has the server, and nothing is bundled or downloaded by us.
 *
 * [AUTO] takes the project's TypeScript 7 when it is there and the bundled vtsls otherwise: a project
 * still on TypeScript 5 or 6 has no `--lsp`, and guessing it would start a server that exits at once.
 * The choice is stored once per application, like the PHP engine.
 */
enum class TsEngine(val id: String) {
  AUTO("auto"),
  VTSLS("vtsls"),
  TSC("tsc"),
}

object TsServerChoice {
  /** First major version of `typescript` whose `tsc` speaks LSP. */
  const val LSP_MAJOR = 7

  /** The `version` of `node_modules/typescript/package.json`, or null when unreadable. */
  fun versionOf(packageJson: String?): String? =
    packageJson?.let { Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }

  fun majorOf(version: String?): Int? = version?.substringBefore('.')?.trim()?.toIntOrNull()

  /** The project's `tsc` launcher: `.cmd` on Windows, where npm writes a batch shim. */
  fun projectTsc(projectBase: Path, windows: Boolean): Path =
    projectBase.resolve("node_modules").resolve(".bin").resolve(if (windows) "tsc.cmd" else "tsc")

  /**
   * The engine that actually starts.
   *
   * [TsEngine.TSC] chosen by hand but not available falls back to vtsls: an explicit choice that
   * cannot start is reported by the doctor, while the editor keeps a working server.
   */
  fun resolve(choice: TsEngine, projectMajor: Int?, projectTscExists: Boolean): TsEngine {
    val tscReady = projectTscExists && (projectMajor ?: 0) >= LSP_MAJOR
    return when (choice) {
      TsEngine.VTSLS -> TsEngine.VTSLS
      TsEngine.AUTO, TsEngine.TSC -> if (tscReady) TsEngine.TSC else TsEngine.VTSLS
    }
  }

  /** The command for the resolved engine; vtsls is the bundled command. */
  fun command(engine: TsEngine, projectBase: Path?, windows: Boolean, vtsls: () -> List<String>): List<String> =
    if (engine == TsEngine.TSC && projectBase != null) listOf(projectTsc(projectBase, windows).toString(), "--lsp", "--stdio")
    else vtsls()

  private const val KEY = "vibe.lsp.ts.engine"

  fun stored(): TsEngine {
    val id = PropertiesComponent.getInstance().getValue(KEY).orEmpty()
    return TsEngine.entries.firstOrNull { it.id == id } ?: TsEngine.AUTO
  }

  fun store(engine: TsEngine) = PropertiesComponent.getInstance().setValue(KEY, engine.id, TsEngine.AUTO.id)

  /** What this project would run, read from its own `node_modules`. */
  fun forProject(choice: TsEngine, projectBase: Path?, windows: Boolean): TsEngine {
    if (projectBase == null) return resolve(choice, null, false)
    val pkg = projectBase.resolve("node_modules").resolve("typescript").resolve("package.json")
    val version = runCatching { Files.readString(pkg) }.getOrNull()?.let { versionOf(it) }
    return resolve(choice, majorOf(version), Files.isRegularFile(projectTsc(projectBase, windows)))
  }
}
