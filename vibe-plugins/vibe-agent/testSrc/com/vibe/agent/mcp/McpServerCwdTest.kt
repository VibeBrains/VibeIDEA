// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.util.ExecutableNames
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The `cwd` of an entry is the working folder of the server process
 *
 * A real process: a shell script that writes the folder it runs in and then answers the handshake and the tool list, so the test
 * sees what a person's server sees. The memory server of VibeMemory finds its project by the place it is started from, and an entry
 * that names a folder must reach it there, not in the project folder it would be started from by default
 */
class McpServerCwdTest {
  private val handshakeMs = 10_000L

  /** Asks nothing of the machine but `/bin/sh`: the folder goes into the file named by the first argument */
  private val script = """
    printf '%s' "${'$'}(pwd -P)" > "${'$'}1"
    while IFS= read -r line; do
      case "${'$'}line" in
        *'"id":'*)
          id=${'$'}{line#*'"id":'}; id=${'$'}{id%%,*}
          case "${'$'}line" in
            *server/discover*) printf '{"jsonrpc":"2.0","id":%s,"error":{"code":-32601,"message":"no"}}\n' "${'$'}id" ;;
            *) printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2025-06-18","tools":[]}}\n' "${'$'}id" ;;
          esac ;;
      esac
    done
  """.trimIndent()

  private fun entry(marker: Path, cwd: String?) =
    McpServersFile.Entry("srv", "/bin/sh", listOf("-c", script, "sh", marker.toString()), cwd = cwd)

  private fun folderOf(entry: McpServersFile.Entry, projectDir: Path?, marker: Path): String {
    McpClient.start(entry, projectDir).use { client ->
      client.initialize("test", handshakeMs)
      client.listTools(handshakeMs)
    }
    return Files.readString(marker)
  }

  @Test
  fun `a server with a cwd runs there, whatever the project folder is`(@TempDir dir: Path) {
    assumeFalse(ExecutableNames.isWindows(), "the script needs a POSIX shell")
    val project = Files.createDirectory(dir.resolve("project"))
    val home = Files.createDirectory(dir.resolve("home"))
    val marker = dir.resolve("marker")
    assertEquals(home.toRealPath().toString(), folderOf(entry(marker, home.toString()), project, marker))
  }

  @Test
  fun `a server without a cwd runs in the project folder`(@TempDir dir: Path) {
    assumeFalse(ExecutableNames.isWindows(), "the script needs a POSIX shell")
    val project = Files.createDirectory(dir.resolve("project"))
    val marker = dir.resolve("marker")
    assertEquals(project.toRealPath().toString(), folderOf(entry(marker, null), project, marker))
  }

  @Test
  fun `a cwd that does not exist is a start that failed, not a start in another folder`(@TempDir dir: Path) {
    assumeFalse(ExecutableNames.isWindows(), "the script needs a POSIX shell")
    val project = Files.createDirectory(dir.resolve("project"))
    val marker = dir.resolve("marker")
    assertFailsWith<com.vibe.agent.acp.AgentLaunchException> {
      McpClient.start(entry(marker, dir.resolve("gone").toString()), project).close()
    }
    assertNull(marker.takeIf { Files.exists(it) }, "the script must not have run anywhere")
  }

  @Test
  fun `the folder of a process is the cwd as written, or the fallback`() {
    val fallback = Path.of("/project")
    assertEquals(Path.of("/Users/me"), McpServersFile.Entry("a", "x", cwd = "/Users/me").processDir(fallback))
    assertEquals(fallback, McpServersFile.Entry("a", "x").processDir(fallback))
    assertNull(McpServersFile.Entry("a", "x").processDir(null))
  }
}
