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
  /**
   * A path the person set in settings beats every other rule, including PATH.
   *
   * «Свой сервер сильнее встроенного» used to hold only for servers that happen to be on PATH, and
   * the projects that most need their own version keep it out of the way — `vendor/bin`, a shared
   * container folder, a checkout built from source. For those the rule silently did not apply.
   */
  fun find(binary: String): String? {
    val dirs = System.getenv("PATH")?.split(java.io.File.pathSeparator).orEmpty().map { Path.of(it) } + EXTRA_DIRS
    // Порядок ВНЕШНИЙ — по именам, а не по каталогам: на Windows в одной папке с `npx.cmd` лежит
    // `npx` для Git Bash, и обход «сначала все имена в первой папке» выбрал бы файл для чужой
    // оболочки. CreateProcess отвечает на это «error=193», не называя причины.
    for (name in com.vibe.agent.util.ExecutableNames.candidates(binary)) {
      dirs.asSequence().map { it.resolve(name) }.firstOrNull { Files.isExecutable(it) }?.let { return it.toString() }
    }
    return null
  }

  // Falls back to the bare name: the failure to start then names exactly what is missing.
  internal fun resolve(binary: String): String = find(binary) ?: binary

  /**
   * Команда из пути, названного человеком.
   *
   * Скрипт на JavaScript исполняемым не бывает: `.js` запускается НОДОЙ, а не сам по себе. Человек,
   * указавший `…/vtsls.js`, имел в виду ровно сервер — и прежде получал бы «Cannot run program».
   */
  internal fun overrideCommand(serverId: String, vararg args: String): List<String>? =
    ServerPaths.overrideFor(serverId)?.let { path ->
      if (NODE_SCRIPTS.any { path.endsWith(it, ignoreCase = true) }) NodeRuntime.command(null, path, *args)
      else listOf(path) + args
    }

  private val NODE_SCRIPTS = listOf(".js", ".cjs", ".mjs")

  fun vtslsCommand(): List<String> =
    overrideCommand(LspDoctor.VTSLS.id, "--stdio")
    ?: nodeServerCommand("vtsls", "--stdio")

  /**
   * The Phpactor phar we ship, or null when running from sources without it.
   *
   * `PathManager.getPluginsPath()` rather than a path relative to the jar: the plugin is a jar
   * inside the distribution, and asking the platform where plugins live is the only spelling that
   * works both in the installer and in a dev run.
   */
  fun bundledPhpactor(): String? = bundled("phpactor.phar")

  /** The launcher that starts the phar on Windows — the whole Windows fix; see [phpactorScript]. */
  fun bundledPhpactorLauncher(): String? = bundled(PHPACTOR_LAUNCHER)

  const val PHPACTOR_LAUNCHER = "phpactorLaunch.php"

  /**
   * The script `php` is told to run: the phar itself, or our launcher on Windows.
   *
   * The phar's own entry point starts with `extension_loaded('posix')` and `exit(255)`, and no
   * Windows build of PHP has that extension — so on Windows the phar can never start itself. The
   * requirement is false for Phpactor (every `posix_*` call inside is guarded, and the server
   * answers `initialize` in full without it — verified 04.09.2026 on Windows 11), so the launcher
   * boots the phar's own autoloader past that one line and changes nothing else.
   *
   * Version 0.4.0 shipped a different attempt — a prepend that switched off Box's requirement
   * checker. It could not work: the phar's own check runs first and does not consult Box. The
   * mistake survived review because it was verified on macOS by disabling the `posix_*` FUNCTIONS,
   * while `extension_loaded('posix')` there stays true — the one thing Windows differs in.
   *
   * No launcher next to the phar (a dev run without the downloaded set) means the phar itself: it
   * then says what is missing instead of failing on a path that does not exist.
   *
   * Pure: OS and both paths are parameters, so the Windows command line is tested on macOS.
   */
  fun phpactorScript(windows: Boolean, launcher: String?, phar: String): String =
    if (windows && launcher != null) launcher else phar

  /**
   * A file inside the servers directory we ship, or null when running from sources without it.
   *
   * Looked up through the PLUGIN's own path, not through `PathManager.getPluginsPath()`: the latter
   * is the USER's plugin directory (`~/Library/Application Support/...`), while our files live
   * inside the installed application. That mistake cost a whole build — LSP4IJ finally loaded, and
   * then Phpactor failed to start with «Cannot run program "phpactor"», because the bundled phar was
   * being looked for in a directory it could never be in.
   *
   * The plugin path is right in both worlds: installed, and run from sources.
   */
  /** A directory inside the servers directory we ship, or null when it is not there. */
  fun bundledDir(vararg parts: String): Path? {
    val root = pluginDir() ?: return null
    val path = parts.fold(root.resolve("servers")) { acc, part -> acc.resolve(part) }
    return path.takeIf { Files.isDirectory(it) }
  }

  private fun bundled(vararg parts: String): String? {
    val root = pluginDir() ?: return null
    val path = parts.fold(root.resolve("servers")) { acc, part -> acc.resolve(part) }
    return path.takeIf { Files.isRegularFile(it) }?.toString()
  }

  private fun pluginDir(): Path? {
    val own = com.intellij.ide.plugins.PluginManagerCore.getPlugin(
      com.intellij.openapi.extensions.PluginId.getId(PLUGIN_ID))?.pluginPath
    if (own != null && Files.isDirectory(own)) return own
    // A dev run may have no descriptor yet; the bundled directory is the honest second guess.
    val bundledDir = com.intellij.openapi.application.PathManager.getBundledPluginsDir().resolve("vibe-lsp")
    return bundledDir.takeIf { Files.isDirectory(it) }
  }

  private const val PLUGIN_ID = "com.vibe.lsp"

  /** Entry points of the Node servers we ship, by binary name. */
  private val BUNDLED_NODE_ENTRY = mapOf(
    "vtsls" to arrayOf("node", "node_modules", "@vtsls", "language-server", "bin", "vtsls.js"),
    "vscode-css-language-server" to arrayOf("node", "node_modules", "vscode-langservers-extracted", "bin", "vscode-css-language-server"),
    "vscode-eslint-language-server" to arrayOf("node", "node_modules", "vscode-langservers-extracted", "bin", "vscode-eslint-language-server"),
    "ngserver" to arrayOf("node", "node_modules", "@angular", "language-server", "bin", "ngserver"),
    "tailwindcss-language-server" to arrayOf("node", "node_modules", "@tailwindcss", "language-server", "bin", "tailwindcss-language-server"),
    "some-sass-language-server" to arrayOf("node", "node_modules", "some-sass-language-server", "bin", "some-sass-language-server"),
  )

  /**
   * Папка, внутри которой лежит наш `node_modules` с серверами, — она же «probe location».
   *
   * Сервер Angular ищет TypeScript и `@angular/language-service` не по PATH, а по указанным
   * путям, и без них падает на старте. Наш набор несёт оба, поэтому первым в списке стоит он, а
   * следом — проект человека: его версии должны побеждать наши, как и у остальных серверов.
   */
  private fun bundledServersRoot(): String? {
    val root = pluginDir() ?: return null
    val node = root.resolve("servers").resolve("node")
    return node.takeIf { Files.isDirectory(it.resolve("node_modules")) }?.toString()
  }

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
    // Интерпретатор ищет NodeRuntime, а не PATH процесса IDE: GUI-приложение шеллового PATH не
    // наследует, и нода, поставленная nvm/fnm/volta/asdf, для `find` не существует вовсе —
    // наружу это выходило как `Cannot run program "node"` (владелец, 18.09.2026).
    bundledNode(binary)?.let { entry -> return NodeRuntime.command(null, entry, *args) }
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
    // A path the person set themselves outranks even their own PATH: they set it precisely because
    // the copy that matters is not the one PATH would find.
    overrideCommand(LspDoctor.PHPACTOR.id, "language-server")?.let { return it }
    find("phpactor")?.let { return listOf(it, "language-server") }
    bundledPhpactor()?.let { phar ->
      val script = phpactorScript(com.vibe.agent.util.ExecutableNames.isWindows(), bundledPhpactorLauncher(), phar)
      return listOf(resolve("php"), script, "language-server")
    }
    return listOf("phpactor", "language-server")
  }

  /**
   * How to start Intelephense: only the person's own installation, because we ship none.
   *
   * `--stdio` is not optional for it: without a transport flag the server starts and says nothing,
   * which looks exactly like a server that failed to start.
   */
  fun intelephenseCommand(): List<String> =
    overrideCommand(LspDoctor.INTELEPHENSE.id, "--stdio")
    ?: nodeServerCommand("intelephense", "--stdio")

  /** The PHP server the settings chose — the only spelling the factory needs to know. */
  fun phpCommand(): List<String> =
    if (PhpServerChoice.effective() == PhpEngine.INTELEPHENSE) intelephenseCommand() else phpactorCommand()

  fun cssCommand(): List<String> =
    overrideCommand(LspDoctor.CSS.id, "--stdio")
    ?: nodeServerCommand("vscode-css-language-server", "--stdio")

  /**
   * Команда сервера Angular: probe-пути обязательны, иначе он не стартует вовсе.
   *
   * Порядок путей — сперва проект, потом наш набор: проект, закрепивший свою версию Angular,
   * должен обслуживаться своей, а наша копия — это запасной вариант, стареющий вместе с IDE.
   */
  fun angularCommand(projectBase: String?): List<String> {
    val probes = listOfNotNull(projectBase, bundledServersRoot())
    overrideCommand(LspDoctor.ANGULAR.id, "--stdio")?.let { return it + probeArgs(probes) }
    val own = find("ngserver")
    if (own != null) return listOf(own, "--stdio") + probeArgs(probes)
    val bundled = bundledNode("ngserver") ?: return listOf("ngserver", "--stdio") + probeArgs(probes)
    return NodeRuntime.command(projectBase, bundled, "--stdio", *probeArgs(probes).toTypedArray())
  }

  private fun probeArgs(probes: List<String>): List<String> {
    if (probes.isEmpty()) return emptyList()
    val joined = probes.joinToString(",")
    return listOf("--tsProbeLocations", joined, "--ngProbeLocations", joined)
  }

  fun tailwindCommand(): List<String> =
    overrideCommand(LspDoctor.TAILWIND.id, "--stdio")
    ?: nodeServerCommand("tailwindcss-language-server", "--stdio")

  fun someSassCommand(): List<String> =
    overrideCommand(LspDoctor.SOME_SASS.id, "--stdio")
    ?: nodeServerCommand("some-sass-language-server", "--stdio")

  fun eslintCommand(): List<String> =
    overrideCommand(LspDoctor.ESLINT.id, "--stdio")
    ?: nodeServerCommand("vscode-eslint-language-server", "--stdio")
}
