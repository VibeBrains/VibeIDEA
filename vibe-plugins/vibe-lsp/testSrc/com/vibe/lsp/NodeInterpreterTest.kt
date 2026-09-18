// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Поиск интерпретатора Node.
 *
 * Менеджеры версий — это раскладки каталогов, и проверять их на живой машине невыносимо: нужна
 * машина, где стоит nvm, машина с fnm, машина без обоих. Поэтому раскладка строится из карты, а
 * поиск от файловой системы отделён.
 *
 * Повод — ошибка владельца 18.09.2026: `Cannot run program "node"`, потому что GUI-приложение не
 * наследует PATH оболочки, а ни одного менеджера версий в списке мест не было вовсе.
 */
class NodeInterpreterTest {
  private val home: Path = Path.of("/Users/dev")

  private class Fake(
    val executables: Set<String> = emptySet(),
    val dirs: Map<String, List<String>> = emptyMap(),
    val files: Map<String, String> = emptyMap(),
    val env: Map<String, String> = emptyMap(),
  ) : NodeInterpreter.Probe {
    override fun isExecutable(path: Path) = path.toString() in executables
    override fun names(dir: Path) = dirs[dir.toString()].orEmpty()
    override fun read(file: Path) = files[file.toString()]
    override fun env(name: String) = env[name]
  }

  private fun detect(probe: NodeInterpreter.Probe, project: Path? = null, configured: String = "",
                     shell: List<Path> = emptyList()) =
    NodeInterpreter.detect(probe, home, project, shell, windows = false, configured = configured)

  @Test
  fun `a path named by hand wins over everything`() {
    val probe = Fake(executables = setOf("/opt/my/node", "/opt/homebrew/bin/node"))
    assertEquals(NodeInterpreter.Outcome.Found("/opt/my/node", NodeInterpreter.Source.SETTING),
                 detect(probe, configured = "/opt/my/node"))
  }

  @Test
  fun `a named path that cannot be run is an error, not a reason to take another node`() {
    // Иначе человек, ошибившийся в пути, молча работает на другой ноде и не понимает, почему
    // проект собирается не так, как в терминале.
    val probe = Fake(executables = setOf("/opt/homebrew/bin/node"))
    assertEquals(NodeInterpreter.Outcome.BadSetting("/opt/my/node"), detect(probe, configured = "/opt/my/node"))
  }

  @Test
  fun `nvm without an alias gives the newest installed version`() {
    val probe = Fake(
      executables = setOf("/Users/dev/.nvm/versions/node/v20.11.1/bin/node", "/Users/dev/.nvm/versions/node/v9.11.2/bin/node"),
      dirs = mapOf("/Users/dev/.nvm/versions/node" to listOf("v9.11.2", "v20.11.1")),
    )
    // v9 против v20: по строке «v9» больше, по версии — меньше.
    assertEquals(NodeInterpreter.Outcome.Found("/Users/dev/.nvm/versions/node/v20.11.1/bin/node", NodeInterpreter.Source.NVM),
                 detect(probe))
  }

  @Test
  fun `the nvm default alias is honoured, including an lts alias`() {
    val probe = Fake(
      executables = setOf("/Users/dev/.nvm/versions/node/v18.20.4/bin/node", "/Users/dev/.nvm/versions/node/v22.3.0/bin/node"),
      dirs = mapOf("/Users/dev/.nvm/versions/node" to listOf("v18.20.4", "v22.3.0")),
      files = mapOf("/Users/dev/.nvm/alias/default" to "lts/hydrogen\n",
                    "/Users/dev/.nvm/alias/lts/hydrogen" to "v18.20.4\n"),
    )
    assertEquals(NodeInterpreter.Outcome.Found("/Users/dev/.nvm/versions/node/v18.20.4/bin/node", NodeInterpreter.Source.NVM),
                 detect(probe))
  }

  @Test
  fun `the project's nvmrc beats the default, and a bare major means its newest`() {
    val probe = Fake(
      executables = setOf("/Users/dev/.nvm/versions/node/v18.20.4/bin/node",
                          "/Users/dev/.nvm/versions/node/v20.2.0/bin/node",
                          "/Users/dev/.nvm/versions/node/v20.11.1/bin/node"),
      dirs = mapOf("/Users/dev/.nvm/versions/node" to listOf("v18.20.4", "v20.2.0", "v20.11.1")),
      files = mapOf("/Users/dev/.nvm/alias/default" to "v18.20.4", "/work/app/.nvmrc" to "20\n"),
    )
    assertEquals(NodeInterpreter.Outcome.Found("/Users/dev/.nvm/versions/node/v20.11.1/bin/node", NodeInterpreter.Source.NVMRC),
                 detect(probe, project = Path.of("/work/app")))
  }

  @Test
  fun `nvm answers to its own variable rather than to the home folder`() {
    val probe = Fake(
      executables = setOf("/opt/nvm/versions/node/v20.0.0/bin/node"),
      dirs = mapOf("/opt/nvm/versions/node" to listOf("v20.0.0")),
      env = mapOf("NVM_DIR" to "/opt/nvm"),
    )
    assertEquals(NodeInterpreter.Outcome.Found("/opt/nvm/versions/node/v20.0.0/bin/node", NodeInterpreter.Source.NVM),
                 detect(probe))
  }

  @Test
  fun `fnm, volta and asdf are each found where they actually put node`() {
    val fnm = Fake(executables = setOf("/Users/dev/.fnm/aliases/default/bin/node"))
    assertEquals(NodeInterpreter.Outcome.Found("/Users/dev/.fnm/aliases/default/bin/node", NodeInterpreter.Source.FNM),
                 detect(fnm))

    val fnmMac = Fake(
      executables = setOf("/Users/dev/Library/Application Support/fnm/node-versions/v22.1.0/installation/bin/node"),
      dirs = mapOf("/Users/dev/Library/Application Support/fnm/node-versions" to listOf("v22.1.0")),
    )
    assertEquals(
      NodeInterpreter.Outcome.Found("/Users/dev/Library/Application Support/fnm/node-versions/v22.1.0/installation/bin/node",
                                    NodeInterpreter.Source.FNM),
      detect(fnmMac))

    assertEquals(NodeInterpreter.Outcome.Found("/Users/dev/.volta/bin/node", NodeInterpreter.Source.VOLTA),
                 detect(Fake(executables = setOf("/Users/dev/.volta/bin/node"))))
    assertEquals(NodeInterpreter.Outcome.Found("/Users/dev/.asdf/shims/node", NodeInterpreter.Source.ASDF),
                 detect(Fake(executables = setOf("/Users/dev/.asdf/shims/node"))))
  }

  @Test
  fun `the shell PATH is used before the well-known folders`() {
    val probe = Fake(executables = setOf("/opt/homebrew/opt/node@24/bin/node", "/opt/homebrew/bin/node"))
    assertEquals(
      NodeInterpreter.Outcome.Found("/opt/homebrew/opt/node@24/bin/node", NodeInterpreter.Source.SHELL_PATH),
      detect(probe, shell = listOf(Path.of("/opt/homebrew/opt/node@24/bin"))))
  }

  @Test
  fun `a machine without node says so instead of inventing a name`() {
    assertEquals(NodeInterpreter.Outcome.Missing, detect(Fake()))
  }

  @Test
  fun `on Windows the executable is node exe and Program Files is consulted`() {
    // Путь собирается тем же `Path.of`, что и в коде: разделители на POSIX-машине свои, и
    // проверяем мы правило поиска (какая папка и какое имя), а не синтаксис путей Windows —
    // его этой машине всё равно не воспроизвести.
    val programFiles = Path.of("C:\\Program Files")
    val node = programFiles.resolve("nodejs").resolve("node.exe").toString()
    val probe = Fake(executables = setOf(node), env = mapOf("ProgramFiles" to programFiles.toString()))
    assertEquals("node.exe", NodeInterpreter.binaryName(windows = true))
    assertEquals(
      NodeInterpreter.Outcome.Found(node, NodeInterpreter.Source.WELL_KNOWN),
      NodeInterpreter.detect(probe, Path.of("C:\\Users\\dev"), null, emptyList(), windows = true, configured = ""))
  }
}
