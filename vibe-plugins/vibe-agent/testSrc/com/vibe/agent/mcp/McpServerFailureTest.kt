// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.acp.AgentLaunchException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a person reads when a server of `.vibe/mcp.json` does not start: a cause and a next step, not the log line */
class McpServerFailureTest {
  @Test
  fun `a program that cannot be found is named, with what to fix`() {
    val error = AgentLaunchException(AgentLaunchException.Kind.NOT_FOUND, "uvx", nodeTool = false)
    assertEquals("не найдена программа uvx — установите её или поправьте command в записи сервера", McpServerFailure.reasonOf(error))
  }

  @Test
  fun `a missing Node tool points to the Node interpreter setting`() {
    val error = AgentLaunchException(AgentLaunchException.Kind.NOT_FOUND, "npx", nodeTool = true)
    val reason = McpServerFailure.reasonOf(error)
    assertTrue(reason.startsWith("не найден npx — серверу нужен Node.js"), reason)
    assertTrue(reason.contains("Интерпретатор Node"), reason)
  }

  @Test
  fun `a start the system refused says what the system said`() {
    val error = AgentLaunchException(AgentLaunchException.Kind.START_FAILED, "/opt/srv", nodeTool = false, detail = "Permission denied")
    assertEquals("не удалось запустить /opt/srv: Permission denied", McpServerFailure.reasonOf(error))
  }

  @Test
  fun `silence past the ceiling is told in seconds`() {
    assertEquals("сервер не ответил за 30 с — проверьте команду, аргументы и переменные окружения",
                 McpServerFailure.reasonOf(McpClient.NoAnswer("initialize", 30_000)))
  }

  @Test
  fun `a process that went away, and a write into its closed pipe, are the same news`() {
    val stopped = McpServerFailure.reasonOf(McpClient.Stopped("server stopped"))
    assertTrue(stopped.startsWith("процесс сервера завершился"), stopped)
    assertEquals(stopped, McpServerFailure.reasonOf(java.io.IOException("Broken pipe")))
  }

  @Test
  fun `wrappers around the failure are taken off`() {
    val wrapped = ExecutionException(McpClient.NoAnswer("tools/list", 5_000))
    assertTrue(McpServerFailure.reasonOf(wrapped).startsWith("сервер не ответил за 5 с"))
  }

  @Test
  fun `an answer of the server itself, or any other failure, keeps its own message`() {
    assertEquals("Invalid params", McpServerFailure.reasonOf(McpClient.McpException("Invalid params")))
    assertEquals("IllegalStateException", McpServerFailure.reasonOf(IllegalStateException()))
  }

  @Test
  fun `the typed exceptions keep the diagnostic text they always had`() {
    assertEquals("initialize: no answer in 30000 ms", McpClient.NoAnswer("initialize", 30_000).message)
    assertTrue(McpClient.Stopped("server stopped") is McpClient.McpException)
    assertTrue(McpClient.NoAnswer("x", 1) is McpClient.McpException)
  }
}
