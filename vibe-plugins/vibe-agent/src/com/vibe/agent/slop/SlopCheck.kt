// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.slop

import java.nio.file.Files
import java.nio.file.Path

/**
 * Where the detector's catalogue comes from, and the one call every surface makes: the MCP tool, the turn gate, the
 * editor action, the design detector and the command line.
 *
 * The catalogue is a file of the shared `.vibe` set read from the build (never seeded — see [SlopCatalog]); a project's
 * `.vibe/slop.json` is read on every check, so an edit applies at once and nothing has to watch the file. No IDE here:
 * the command-line entry point runs this without an application.
 */
object SlopCheck {
  /** The catalogue inside the shared set, as the build carries it. */
  const val CATALOG = "slop/catalog.jsonc"

  /** The project's overrides inside `.vibe/`. */
  const val PROJECT_FILE = "slop.json"

  /** Files an agent writes for people to read: the ones the turn gate checks. */
  val PROSE_EXTENSIONS: List<String> = listOf(".md", ".mdx", ".markdown", ".txt", ".rst", ".adoc")

  /** What went wrong reading the shipped catalogue; empty in a sound build, and a test holds it there. */
  val builtInWarnings: List<String> get() = shipped.second

  private val shipped: Pair<CompiledCatalog?, List<String>> by lazy {
    val warnings = ArrayList<String>()
    val text = com.vibe.agent.defaults.VibeDefaults.setFile(CATALOG)
    if (text == null) {
      warnings.add("$CATALOG is missing from the build")
      return@lazy null to warnings
    }
    val catalog = SlopCatalog.parse(text) { warnings.add(it) }
    catalog?.let { CompiledCatalog.of(it) { w -> warnings.add(w) } } to warnings
  }

  /** The shipped catalogue; null when the build carries none that parses — callers say so rather than pass silently. */
  val builtIn: CompiledCatalog? get() = shipped.first

  /** The catalogue as a project sees it: the shipped one with the project's `.vibe/slop.json` applied. */
  fun catalog(projectBase: String?, onWarning: (String) -> Unit): CompiledCatalog? =
    withOverrides(projectBase?.let { Path.of(it, ".vibe", PROJECT_FILE) }, onWarning)

  /** The shipped catalogue with an overrides file applied — the command line names its file directly. */
  fun withOverrides(file: Path?, onWarning: (String) -> Unit): CompiledCatalog? {
    val base = builtIn ?: return null
    if (file == null || !Files.isRegularFile(file)) return base
    val text = runCatching { Files.readString(file) }.getOrElse {
      onWarning("$file: ${it.message}")
      return base
    }
    return SlopOverrides.parse(text, onWarning).applyTo(base, onWarning)
  }

  fun check(text: String, projectBase: String?, budget: SlopBudget, onWarning: (String) -> Unit): SlopReport? =
    catalog(projectBase, onWarning)?.let { TextSlop.analyze(text, it, budget) }

  fun isProse(path: String): Boolean {
    val lower = path.lowercase()
    return PROSE_EXTENSIONS.any { lower.endsWith(it) }
  }
}
