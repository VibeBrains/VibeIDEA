// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.ide.util.PropertiesComponent
import java.nio.file.Files
import java.nio.file.Path

/**
 * Which `node` starts the language servers.
 *
 * Three of the four servers we ship are Node programs, and so is the JavaScript debug adapter. Until
 * now they were started by the bare name, looked up in the PATH of the IDE process plus four
 * well-known folders — and a GUI application on macOS does not inherit the shell's PATH, while a
 * developer's Node is usually installed by a version manager that is not in any folder we knew.
 * What came out was a Java exception in the editor: `Cannot run program "node" ... error=2`
 * (brought by the owner 18.09.2026 on a project at `/Volumes/ExVint/…`).
 *
 * So the interpreter is resolved deliberately, in a stated order, and the person can always name it
 * by hand — the same page where the PHP and TypeScript engines are chosen.
 *
 * The search order and why it is this order:
 *  1. **the setting** — a named path beats every rule, and a named path that cannot run is an error
 *     rather than a reason to quietly use another one;
 *  2. **`.nvmrc` of the project** — this is what makes ONE application-wide setting enough: a project
 *     pinned to an older Node says so in its own file, and asking for a second settings page per
 *     project would be asking people to repeat themselves;
 *  3. **version managers** — nvm, fnm, volta, asdf, in that order;
 *  4. **the login shell's PATH** — whatever the person can run in a terminal;
 *  5. **well-known folders** — Homebrew, `/usr/local`, `/usr/bin`, Program Files on Windows.
 *
 * Pure against a [Probe]: version managers are directory layouts, and a layout is exactly the thing
 * that is unbearable to test on a real machine — the tests build nvm, fnm and volta out of maps.
 */
object NodeInterpreter {
  /** Where the interpreter came from — said out loud, because «нашёл» without «где» is not an answer. */
  enum class Source { SETTING, NVMRC, NVM, FNM, VOLTA, ASDF, SHELL_PATH, WELL_KNOWN }

  sealed interface Outcome {
    data class Found(val path: String, val source: Source) : Outcome
    /** The person named a path and it cannot be run: never silently replaced by another Node. */
    data class BadSetting(val path: String) : Outcome
    data object Missing : Outcome
  }

  /** The file system, as much of it as the search needs. */
  interface Probe {
    fun isExecutable(path: Path): Boolean
    /** Names inside a directory, empty when it is not there. */
    fun names(dir: Path): List<String>
    /** Text of a file, null when it is not there. */
    fun read(file: Path): String?
    fun env(name: String): String?
  }

  object RealProbe : Probe {
    override fun isExecutable(path: Path): Boolean = runCatching { Files.isExecutable(path) }.getOrDefault(false)
    override fun names(dir: Path): List<String> =
      runCatching { Files.list(dir).use { stream -> stream.map { it.fileName.toString() }.toList() } }.getOrDefault(emptyList())
    override fun read(file: Path): String? = runCatching { Files.readString(file) }.getOrNull()
    override fun env(name: String): String? = System.getenv(name)
  }

  const val KEY = "vibe.lsp.node"

  /** The path the person named, or empty for «автоматически». */
  fun stored(): String = PropertiesComponent.getInstance().getValue(KEY).orEmpty()

  fun store(value: String) {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) PropertiesComponent.getInstance().unsetValue(KEY)
    else PropertiesComponent.getInstance().setValue(KEY, trimmed)
  }

  fun binaryName(windows: Boolean): String = if (windows) "node.exe" else "node"

  /**
   * The interpreter for this project, by the order above.
   *
   * [shellPath] is the PATH of the login shell — the platform reads it for us
   * (`EnvironmentUtil.getEnvironmentMap()`); handing it in keeps this function pure.
   */
  fun detect(
    probe: Probe,
    home: Path,
    projectBase: Path?,
    shellPath: List<Path>,
    windows: Boolean,
    configured: String,
  ): Outcome {
    val exe = binaryName(windows)
    configured.trim().takeIf { it.isNotEmpty() }?.let { named ->
      val path = Path.of(named)
      return if (probe.isExecutable(path)) Outcome.Found(named, Source.SETTING) else Outcome.BadSetting(named)
    }
    nvm(probe, home, projectBase, exe)?.let { return it }
    fnm(probe, home, exe)?.let { return it }
    executable(probe, home.resolve(".volta").resolve("bin").resolve(exe), Source.VOLTA)?.let { return it }
    executable(probe, home.resolve(".asdf").resolve("shims").resolve(exe), Source.ASDF)?.let { return it }
    for (dir in shellPath) executable(probe, dir.resolve(exe), Source.SHELL_PATH)?.let { return it }
    for (dir in wellKnownDirs(probe, home, windows)) executable(probe, dir.resolve(exe), Source.WELL_KNOWN)?.let { return it }
    return Outcome.Missing
  }

  /** Folders the search looked in, for the message that says «не нашёл» — a list, not a shrug. */
  fun searchedPlaces(home: Path, windows: Boolean, shellPath: List<Path>): List<String> =
    (listOf(home.resolve(".nvm"), home.resolve(".fnm"), home.resolve(".volta"), home.resolve(".asdf")) +
     shellPath + wellKnownDirs(RealProbe, home, windows)).map { it.toString() }.distinct()

  // --- nvm ---------------------------------------------------------------------------------

  private fun nvm(probe: Probe, home: Path, projectBase: Path?, exe: String): Outcome? {
    val root = probe.env(NVM_DIR)?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: home.resolve(".nvm")
    val versionsDir = root.resolve("versions").resolve("node")
    val installed = probe.names(versionsDir).filter { it.startsWith("v") }.sortedWith(VERSION_ORDER)
    if (installed.isEmpty()) return null
    // The project's own wish comes first — this is what keeps one application-wide setting honest.
    projectBase?.let { base ->
      probe.read(base.resolve(".nvmrc"))?.trim()?.takeIf { it.isNotEmpty() }?.let { wanted ->
        match(wanted, installed, probe, root)?.let { version ->
          executable(probe, nodeOf(versionsDir, version, exe), Source.NVMRC)?.let { return it }
        }
      }
    }
    val fromAlias = probe.read(root.resolve("alias").resolve("default"))?.trim()?.takeIf { it.isNotEmpty() }
      ?.let { match(it, installed, probe, root) }
    val version = fromAlias ?: installed.last()
    return executable(probe, nodeOf(versionsDir, version, exe), Source.NVM)
  }

  private fun nodeOf(versionsDir: Path, version: String, exe: String): Path =
    versionsDir.resolve(version).resolve("bin").resolve(exe)

  /**
   * The installed version a request names: `20.11.1`, `20`, `v20`, or an alias like `lts/iron`.
   *
   * An alias is resolved through its own file, one hop — `lts/iron` is a file holding a version, and
   * a chain of aliases pointing at each other is a broken nvm, not a case to support.
   */
  private fun match(wanted: String, installed: List<String>, probe: Probe, nvmRoot: Path): String? {
    val cleaned = wanted.trim()
    if (cleaned.contains('/')) {
      val alias = probe.read(nvmRoot.resolve("alias").resolve(cleaned))?.trim()?.takeIf { it.isNotEmpty() }
      return alias?.let { match(it, installed, probe, nvmRoot) }
    }
    val bare = cleaned.removePrefix("v")
    if (bare.isEmpty()) return null
    installed.firstOrNull { it == "v$bare" }?.let { return it }
    // `20` means the newest 20.x, the way every version manager reads it.
    return installed.filter { it.removePrefix("v").startsWith("$bare.") }.maxWithOrNull(VERSION_ORDER)
  }

  // --- fnm ---------------------------------------------------------------------------------

  private fun fnm(probe: Probe, home: Path, exe: String): Outcome? {
    val roots = listOfNotNull(
      probe.env(FNM_DIR)?.takeIf { it.isNotBlank() }?.let { Path.of(it) },
      home.resolve(".fnm"),
      home.resolve("Library").resolve("Application Support").resolve("fnm"),
      home.resolve(".local").resolve("share").resolve("fnm"),
    )
    for (root in roots) {
      executable(probe, root.resolve("aliases").resolve("default").resolve("bin").resolve(exe), Source.FNM)?.let { return it }
      val versionsDir = root.resolve("node-versions")
      val installed = probe.names(versionsDir).filter { it.startsWith("v") }.sortedWith(VERSION_ORDER)
      installed.lastOrNull()?.let { version ->
        executable(probe, versionsDir.resolve(version).resolve("installation").resolve("bin").resolve(exe), Source.FNM)
          ?.let { return it }
      }
    }
    return null
  }

  // --- the rest ----------------------------------------------------------------------------

  private fun wellKnownDirs(probe: Probe, home: Path, windows: Boolean): List<Path> =
    if (windows) listOfNotNull(
      probe.env("ProgramFiles")?.let { Path.of(it, "nodejs") },
      probe.env("ProgramFiles(x86)")?.let { Path.of(it, "nodejs") },
      home.resolve("AppData").resolve("Roaming").resolve("npm"),
    )
    else listOf(
      Path.of("/opt/homebrew/bin"),
      Path.of("/usr/local/bin"),
      Path.of("/usr/bin"),
      home.resolve(".local").resolve("bin"),
      home.resolve(".npm-global").resolve("bin"),
    )

  private fun executable(probe: Probe, path: Path, source: Source): Outcome.Found? =
    if (probe.isExecutable(path)) Outcome.Found(path.toString(), source) else null

  /** `v20.11.1` before `v22.0.0`, and `v9` before `v10` — string order would put `v9` last. */
  private val VERSION_ORDER: Comparator<String> = Comparator { left, right ->
    val a = parts(left)
    val b = parts(right)
    var index = 0
    var verdict = 0
    while (verdict == 0 && index < maxOf(a.size, b.size)) {
      verdict = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
      index++
    }
    if (verdict != 0) verdict else left.compareTo(right)
  }

  private fun parts(version: String): List<Int> =
    version.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }

  private const val NVM_DIR = "NVM_DIR"
  private const val FNM_DIR = "FNM_DIR"
}
