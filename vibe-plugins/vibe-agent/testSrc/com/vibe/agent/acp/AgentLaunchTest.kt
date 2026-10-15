// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.acp

import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Finding and starting an agent: the environment of a GUI app on macOS, which is four system folders, against the one
 * the person's terminal has. No process is started here: the file system is a set of paths.
 */
class AgentLaunchTest {
  private val home = "/Users/dev"
  private val finderPath = "/usr/bin:/bin:/usr/sbin:/sbin"
  private val nvmBin = "$home/.nvm/versions/node/v20.11.1/bin"
  private val settingNode = "/opt/node-lts/bin"

  private fun setup(shellPath: String? = null, nodeDir: String? = null, shellExtra: Map<String, String> = emptyMap()) =
    AgentLaunch.setup(
      processEnv = mapOf("PATH" to finderPath, "HOME" to home),
      shellEnv = if (shellPath == null) emptyMap() else mapOf("PATH" to shellPath) + shellExtra,
      nodeDir = nodeDir,
      home = home,
    )

  private fun plan(command: String, setup: AgentLaunch.Setup, existing: Set<String>, windows: Boolean = false) =
    AgentLaunch.plan(command, setup, windows, ".COM;.EXE;.BAT;.CMD") { it.toString() in existing }

  private fun dirsOf(overlay: Map<String, String>): List<String> = overlay.getValue("PATH").split(File.pathSeparator)

  @Test
  fun `npx of Homebrew is found though the app was started with four system folders`() {
    val ready = plan("npx", setup(), setOf("/opt/homebrew/bin/npx")) as AgentLaunch.Plan.Ready
    assertEquals("/opt/homebrew/bin/npx", ready.executable)
  }

  @Test
  fun `npx of a version manager is found through the PATH of the login shell`() {
    val shell = "$nvmBin:/opt/homebrew/bin:$finderPath"
    val ready = plan("npx", setup(shellPath = shell), setOf("$nvmBin/npx")) as AgentLaunch.Plan.Ready
    assertEquals("$nvmBin/npx", ready.executable)
  }

  @Test
  fun `the interpreter set in the IDE is first, so its npx beats the one the shell finds`() {
    val shell = "$nvmBin:$finderPath"
    val both = setOf("$nvmBin/npx", "$settingNode/npx")
    val ready = plan("npx", setup(shellPath = shell, nodeDir = settingNode), both) as AgentLaunch.Plan.Ready
    assertEquals("$settingNode/npx", ready.executable)
    assertEquals(settingNode, dirsOf(ready.overlay).first(), "the child finds the same node beside it")
  }

  @Test
  fun `the child gets the same folders the search used, each once`() {
    val shell = "$nvmBin:$finderPath"
    val setup = setup(shellPath = shell, nodeDir = nvmBin)
    val ready = plan("npx", setup, setOf("$nvmBin/npx")) as AgentLaunch.Plan.Ready
    assertEquals(setup.searchDirs, dirsOf(ready.overlay))
    assertEquals(setup.searchDirs.distinct(), setup.searchDirs)
    assertTrue("/opt/homebrew/bin" in setup.searchDirs && "$home/.local/bin" in setup.searchDirs, "the well-known folders are last")
    assertEquals(nvmBin, setup.searchDirs.first())
  }

  @Test
  fun `a missing npx is a missing Node tool, a missing agent program is not`() {
    val nothing = emptySet<String>()
    assertEquals(AgentLaunch.Plan.Missing("npx", nodeTool = true), plan("npx", setup(), nothing))
    assertEquals(AgentLaunch.Plan.Missing("claude-agent-acp", nodeTool = false), plan("claude-agent-acp", setup(), nothing))
  }

  @Test
  fun `a file that is not executable is not found`() {
    val dirOnly = plan("npx", setup(), existing = emptySet())
    assertTrue(dirOnly is AgentLaunch.Plan.Missing)
  }

  @Test
  fun `the variables of the login shell go to the child and its PATH is replaced, not doubled`() {
    val setup = setup(shellPath = "/opt/homebrew/bin:$finderPath", shellExtra = mapOf("NVM_DIR" to "$home/.nvm", "Path" to "ignored"))
    assertEquals("$home/.nvm", setup.overlay["NVM_DIR"])
    assertEquals(1, setup.overlay.keys.count { it.equals("PATH", ignoreCase = true) })
    assertFalse("ignored" in setup.overlay.values)
  }

  @Test
  fun `a command that is a path is checked as it stands and its folder is added at the end of PATH`() {
    val script = "$home/tools/agent/bin/agent-acp"
    val ready = plan(script, setup(), setOf(script)) as AgentLaunch.Plan.Ready
    assertEquals(script, ready.executable)
    assertEquals("$home/tools/agent/bin", dirsOf(ready.overlay).last())
    assertEquals(AgentLaunch.Plan.Missing(script, nodeTool = false), plan(script, setup(), emptySet()))
  }

  @Test
  fun `on Windows the cmd file is chosen before the bare script that cmd cannot run`() {
    // No drive letter: the test runs on any OS, and the PATH separator of the OS under it would cut "C:" in two
    val dir = "Program Files\\nodejs"
    val setup = AgentLaunch.setup(mapOf("Path" to dir), emptyMap(), null, "Users\\dev")
    val files = setOf(Path.of(dir, "npx").toString(), Path.of(dir, "npx.CMD").toString())
    val ready = AgentLaunch.plan("npx", setup, windows = true, pathext = ".COM;.EXE;.BAT;.CMD") { it.toString() in files }
    assertTrue(ready is AgentLaunch.Plan.Ready && ready.executable.endsWith("npx.CMD"), ready.toString())
  }

  @Test
  fun `a PATH entry the file system cannot parse is skipped, not fatal`() {
    val setup = AgentLaunch.setup(mapOf("PATH" to "bad\u0000dir:/opt/homebrew/bin"), emptyMap(), null, home)
    val ready = plan("npx", setup, setOf("/opt/homebrew/bin/npx"))
    assertTrue(ready is AgentLaunch.Plan.Ready, ready.toString())
  }

  @Test
  fun `node, npx and npm are the tools of Node, by name or by path, with or without an extension`() {
    listOf("node", "npx", "npm", "/usr/local/bin/npx", "C:\\Program Files\\nodejs\\NPX.CMD", "node.exe").forEach {
      assertTrue(AgentLaunch.isNodeTool(it), it)
    }
    listOf("claude", "claude-agent-acp", "npm-check", "pnpm", "/opt/tools/nodeish").forEach { assertFalse(AgentLaunch.isNodeTool(it), it) }
  }

  @Test
  fun `the reason of the system is taken out of the words of the Java exception`() {
    val withDir = IOException("Cannot run program \"agent\" (in directory \"/Volumes/x/work\"): error=13, Permission denied")
    assertEquals("error=13, Permission denied", AgentLaunch.systemReason(withDir))
    val modern = IOException("Cannot run program \"npx\" (in directory \"/p\"): Exec failed, error: 2 (No such file or directory)")
    assertEquals("Exec failed, error: 2 (No such file or directory)", AgentLaunch.systemReason(modern))
    assertEquals("error=2, No such file", AgentLaunch.systemReason(IOException("Cannot run program \"a\": error=2, No such file")))
    assertEquals("something else", AgentLaunch.systemReason(IOException("something else")))
    assertEquals("", AgentLaunch.systemReason(IOException()))
  }

  @Test
  fun `the person reads a missing Node tool with the way out, and a refusal with what the system said`() {
    val node = AgentLaunchException(AgentLaunchException.Kind.NOT_FOUND, "npx", nodeTool = true)
    val text = AgentFailure.reasonOf(java.util.concurrent.ExecutionException(node))
    assertTrue(text.startsWith("Не найден npx") && "Node.js" in text && "Интерпретатор Node" in text, text)
    assertFalse("Cannot run program" in text, text)
    assertTrue(AgentFailure.needsNodeSettings(node))

    val other = AgentLaunchException(AgentLaunchException.Kind.NOT_FOUND, "claude-agent-acp", nodeTool = false)
    assertTrue(AgentFailure.reasonOf(other).startsWith("Не найдена программа агента: claude-agent-acp"))
    assertFalse(AgentFailure.needsNodeSettings(other))

    val refused = AgentLaunchException(
      AgentLaunchException.Kind.START_FAILED, "agent", nodeTool = false, detail = "error=13, Permission denied",
    )
    assertEquals("Не удалось запустить agent: error=13, Permission denied", AgentFailure.reasonOf(refused))
    assertFalse(AgentFailure.needsNodeSettings(refused))
    assertNull(refused.cause)
  }
}
