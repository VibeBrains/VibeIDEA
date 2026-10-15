// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.acp

import com.vibe.agent.node.NodeRuntime
import com.vibe.agent.util.ExecutableNames
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The process of an external agent could not be started; the words for the person are made by [AgentFailure.reasonOf].
 * The message is the diagnostic text for the log.
 */
class AgentLaunchException(
  val kind: Kind,
  val command: String,
  /** The command is one of Node's own, so the way out is a Node interpreter in the settings */
  val nodeTool: Boolean,
  /** What the system said, for [Kind.START_FAILED] */
  val detail: String = "",
  cause: Throwable? = null,
) : IOException("cannot start the agent command '$command': $kind $detail".trim(), cause) {
  enum class Kind {
    /** No executable of that name in any folder of the search */
    NOT_FOUND,

    /** The file was found and the system refused to start it */
    START_FAILED,
  }
}

/**
 * How an agent process is found and started: with the environment the person's terminal has.
 *
 * A GUI application on macOS is started by launchd with a PATH of four system folders, so `npx` set up by Homebrew or
 * a version manager was invisible and the start ended in `Cannot run program "npx"`. The same cause already cost the
 * language servers their Node, and the answer here is the same: the login shell's environment (the platform reads it
 * once, [com.intellij.util.EnvironmentUtil]) plus the folder of the Node interpreter set in the IDE, put first.
 *
 * Pure against its inputs: the machine is read in [machine] and [planHere] only, so the search is tested with maps.
 */
object AgentLaunch {
  /** Programs that come with Node: when one is missing the fix is a Node interpreter, not another agent command */
  private val NODE_TOOLS = setOf("node", "npx", "npm")

  private const val PATH = "PATH"

  /**
   * The environment to lay over the IDE's own, and the folders the executable is looked for in.
   *
   * [searchDirs] and the `PATH` in [overlay] are the same list: what the search can find, the child can find too.
   */
  class Setup(val overlay: Map<String, String>, val searchDirs: List<String>)

  sealed interface Plan {
    data class Ready(val executable: String, val overlay: Map<String, String>) : Plan
    data class Missing(val command: String, val nodeTool: Boolean) : Plan
  }

  /**
   * The folders, in order: the Node interpreter's, the login shell's PATH, the IDE's PATH, then the well-known folders
   * of user-level installs that no PATH names.
   */
  fun setup(processEnv: Map<String, String>, shellEnv: Map<String, String>, nodeDir: String?, home: String): Setup {
    val dirs = LinkedHashSet<String>()
    nodeDir?.takeIf { it.isNotBlank() }?.let { dirs.add(it) }
    for (env in listOf(shellEnv, processEnv)) pathOf(env).split(File.pathSeparator).filter { it.isNotBlank() }.forEach { dirs.add(it) }
    dirs.addAll(wellKnownDirs(home))
    val searchDirs = dirs.toList()
    val overlay = shellEnv.filterKeys { !it.equals(PATH, ignoreCase = true) } + (PATH to searchDirs.joinToString(File.pathSeparator))
    return Setup(overlay, searchDirs)
  }

  /** The PATH of an environment; on Windows the variable is spelled `Path`, hence the case-insensitive match */
  private fun pathOf(env: Map<String, String>): String =
    env.entries.firstOrNull { it.key.equals(PATH, ignoreCase = true) }?.value.orEmpty()

  /**
   * Folders where Node and agent CLIs usually live, for an IDE whose login shell PATH could not be read
   * Version managers are included: nvm, fnm, volta, asdf and mise put Node outside every system folder
   */
  private fun wellKnownDirs(home: String): List<String> = listOf(
    Path.of(home, ".local", "bin").toString(),
    Path.of(home, ".npm-global", "bin").toString(),
    Path.of(home, ".volta", "bin").toString(),
    Path.of(home, ".asdf", "shims").toString(),
    Path.of(home, ".local", "share", "mise", "shims").toString(),
    Path.of(home, ".fnm", "aliases", "default", "bin").toString(),
    Path.of(home, "Library", "Application Support", "fnm", "aliases", "default", "bin").toString(),
    "/opt/homebrew/bin",
    "/usr/local/bin",
  ) + listOfNotNull(newestNvmBin(home))

  /** The bin folder of the newest Node that nvm installed; nvm keeps no stable link to the default one */
  private fun newestNvmBin(home: String): String? {
    val versions = File(home, NVM_VERSIONS).listFiles { f -> f.isDirectory && f.name.startsWith("v") } ?: return null
    val newest = versions.maxWithOrNull(compareBy<File, List<Int>>(NUMBERS_FIRST) { versionParts(it.name) }) ?: return null
    return File(newest, "bin").path
  }

  private fun versionParts(name: String): List<Int> = name.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }

  private val NUMBERS_FIRST: Comparator<List<Int>> = Comparator { a, b ->
    a.zip(b).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: a.size.compareTo(b.size)
  }

  private const val NVM_VERSIONS = ".nvm/versions/node"

  /** Whether the command is `node`, `npx` or `npm`, by name or by path, with or without a Windows extension */
  fun isNodeTool(command: String): Boolean {
    val name = command.substringAfterLast('/').substringAfterLast('\\')
    return name.substringBeforeLast('.').lowercase() in NODE_TOOLS
  }

  /**
   * The file that will run for [command], or null when there is none.
   *
   * A command that is already a path is checked as it stands; a bare name is looked for in [dirs]. Extensions come
   * first on Windows (see [ExecutableNames.candidates]).
   */
  fun find(command: String, dirs: List<String>, windows: Boolean, pathext: String, isExecutable: (Path) -> Boolean): String? {
    val names = ExecutableNames.candidates(command, windows, pathext)
    if (ExecutableNames.looksLikePath(command)) return names.firstOrNull { safePath(it)?.let(isExecutable) == true }
    for (name in names) {
      for (dir in dirs) {
        val candidate = safePath(dir, name) ?: continue
        if (isExecutable(candidate)) return candidate.toString()
      }
    }
    return null
  }

  /** A PATH entry the file system cannot parse (a stray quote, a NUL) is skipped, not a failure of the whole search */
  private fun safePath(first: String, vararg more: String): Path? = runCatching { Path.of(first, *more) }.getOrNull()

  /**
   * What to start for [command]. A command that is a path gets its own folder at the end of PATH: an agent installed as
   * a Node script starts with `#!/usr/bin/env node` and needs the `node` beside it.
   */
  fun plan(command: String, setup: Setup, windows: Boolean, pathext: String, isExecutable: (Path) -> Boolean): Plan {
    val executable = find(command, setup.searchDirs, windows, pathext, isExecutable) ?: return Plan.Missing(command, isNodeTool(command))
    val own = safePath(executable)?.parent?.toString()
    val dirs = if (own == null || own in setup.searchDirs) setup.searchDirs else setup.searchDirs + own
    return Plan.Ready(executable, setup.overlay + (PATH to dirs.joinToString(File.pathSeparator)))
  }

  /** `Cannot run program "x" (in directory "y"): reason` → `reason`; any other message stays as it is */
  fun systemReason(error: IOException): String {
    val message = error.message.orEmpty()
    return START_ERROR.matchEntire(message)?.groupValues?.get(1)?.trim()?.ifEmpty { null } ?: message
  }

  private val START_ERROR = Regex("""Cannot run program "[^"]*"(?: \(in directory "[^"]*"\))?: (.*)""", RegexOption.DOT_MATCHES_ALL)

  /** The real machine: the login shell's environment, the IDE's own, and the interpreter set in the IDE for [projectBase] */
  fun machine(projectBase: String?): Setup = setup(
    processEnv = System.getenv(),
    shellEnv = runCatching { com.intellij.util.EnvironmentUtil.getEnvironmentMap() }.getOrDefault(emptyMap()),
    nodeDir = NodeRuntime.binDir(projectBase),
    home = System.getProperty("user.home").orEmpty(),
  )

  /** [plan] on the real machine */
  fun planHere(command: String, projectBase: String?): Plan = plan(
    command, machine(projectBase), ExecutableNames.isWindows(),
    System.getenv("PATHEXT") ?: ExecutableNames.DEFAULT_PATHEXT,
  ) { Files.isRegularFile(it) && Files.isExecutable(it) }

  /** The plan for [command] on the real machine; a program that is not there throws [AgentLaunchException] */
  fun readyHere(command: String, projectBase: String?): Plan.Ready = when (val plan = planHere(command, projectBase)) {
    is Plan.Ready -> plan
    is Plan.Missing -> throw AgentLaunchException(AgentLaunchException.Kind.NOT_FOUND, command, plan.nodeTool)
  }

  /** `builder.start()` that tells a refusal of the system in the words of [AgentLaunchException], not as a raw IOException */
  fun start(builder: ProcessBuilder, command: String): Process = try {
    builder.start()
  }
  catch (e: IOException) {
    throw AgentLaunchException(AgentLaunchException.Kind.START_FAILED, command, isNodeTool(command), systemReason(e), e)
  }
}
