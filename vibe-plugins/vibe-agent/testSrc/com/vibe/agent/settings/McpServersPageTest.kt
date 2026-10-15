// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.settings

import com.vibe.agent.mcp.ConfiguredServersSource
import com.vibe.agent.mcp.McpServersEdit
import com.vibe.agent.mcp.McpServersFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The state each card shows for a server: what the file says, what the chat that runs the servers knows */
class McpServersPageTest {
  private fun entry(name: String, command: String = "npx", args: List<String> = emptyList(), env: Map<String, String> = emptyMap(),
                    disabled: Boolean = false) = McpServersFile.Entry(name, command, args, env, disabled)

  private fun report(live: Map<String, ConfiguredServersSource.Report.Live> = emptyMap(), failed: Map<String, String> = emptyMap()) =
    ConfiguredServersSource.Report(live, failed)

  private fun live(tools: Int = 3, withheld: Boolean = false, alive: Boolean = true) =
    ConfiguredServersSource.Report.Live(tools, withheld, alive)

  private fun card(entry: McpServersFile.Entry, report: ConfiguredServersSource.Report, canRestart: Boolean = true) =
    McpServersPage.cards(listOf(entry), report, canRestart).single()

  @Test
  fun `a server nobody has started is idle and says so, not stopped`() {
    val card = card(entry("a"), report())
    assertEquals(McpServersPage.Status.IDLE, card.status)
    assertEquals("ещё не запускался", McpServersPage.statusText(card))
    assertFalse(card.restartable, "there is nothing to restart: the chat has not touched it")
  }

  @Test
  fun `a running server counts its tools and can be restarted when a chat holds it`() {
    val running = report(live = mapOf("a" to live(tools = 7)))
    val card = card(entry("a"), running)
    assertEquals(McpServersPage.Status.RUNNING, card.status)
    assertEquals("запущен · инструментов: 7", McpServersPage.statusText(card))
    assertTrue(card.restartable)
    assertFalse(card(entry("a"), running, canRestart = false).restartable, "no chat holds the servers: a restart would reach nobody")
  }

  @Test
  fun `a server that did not start carries the reason in words`() {
    val card = card(entry("a"), report(failed = mapOf("a" to "не найдена программа npx")))
    assertEquals(McpServersPage.Status.FAILED, card.status)
    assertEquals("не запустился: не найдена программа npx", McpServersPage.statusText(card))
    assertTrue(card.restartable, "a failed server is the first one a person wants to retry")
  }

  @Test
  fun `a process that went away after it started is a failure with its own reason`() {
    val card = card(entry("a"), report(live = mapOf("a" to live(alive = false))))
    assertEquals(McpServersPage.Status.FAILED, card.status)
    assertTrue(card.reason!!.contains("завершился"), card.reason)
  }

  @Test
  fun `tools held back wait for the person, who can confirm them`() {
    val card = card(entry("a"), report(live = mapOf("a" to live(tools = 4, withheld = true))))
    assertEquals(McpServersPage.Status.WITHHELD, card.status)
    assertTrue(card.confirmable)
    assertEquals("инструменты ждут подтверждения", McpServersPage.statusText(card))
    assertFalse(card(entry("a"), report(live = mapOf("a" to live()))).confirmable, "nothing to confirm on a server that runs")
  }

  @Test
  fun `a server switched off is off whatever the chat remembers of it`() {
    val stale = report(live = mapOf("a" to live()), failed = mapOf("a" to "старая причина"))
    val card = card(entry("a", disabled = true), stale)
    assertEquals(McpServersPage.Status.DISABLED, card.status)
    assertTrue(card.disabled)
    assertEquals("выключен", McpServersPage.statusText(card))
    assertFalse(card.restartable)
  }

  @Test
  fun `cards follow the file, one per entry`() {
    val cards = McpServersPage.cards(listOf(entry("b"), entry("a"), entry("c")), report(), canRestart = true)
    assertEquals(listOf("b", "a", "c"), cards.map { it.name })
  }

  @Test
  fun `the command line is readable and the environment is shown by name only`() {
    val card = card(entry("a", "npx", listOf("-y", "pkg", "--name", "a b", ""), mapOf("GITHUB_TOKEN" to "ghp_secret")), report())
    assertEquals("npx -y pkg --name \"a b\" \"\"", card.commandLine)
    assertEquals(listOf("GITHUB_TOKEN"), card.envNames)
    assertFalse(card.commandLine.contains("ghp_secret"))
  }

  @Test
  fun `the summary counts the file and the servers that run`() {
    val cards = McpServersPage.cards(listOf(entry("a"), entry("b"), entry("c", disabled = true)), report(live = mapOf("a" to live())), true)
    assertEquals("серверов: 3 · запущено: 1", McpServersPage.summary(cards))
  }

  @Test
  fun `what the file says wrong about itself is said in the words of the chat feed`() {
    val parsed = McpServersFile.parse("""{"mcpServers": {"пустой": {}, "рабочий": {"command": "node"}}}""")
    assertEquals(listOf("mcp.json: у записи «пустой» не указан command"), McpServersPage.problems(parsed))
    assertEquals(listOf("mcp.json: файл не разобрался как JSON"), McpServersPage.problems(McpServersFile.parse("это не json")))
    assertTrue(McpServersPage.problems(McpServersFile.EMPTY).isEmpty(), "no file is not a complaint")
  }

  @Test
  fun `every reason an edit is refused has words`() {
    for (reason in McpServersEdit.Reason.entries) {
      val text = McpServersPage.refusal(reason, "github")
      assertTrue(text.isNotBlank() && !text.startsWith("settings.") && !text.startsWith("{"), "$reason -> $text")
    }
    assertTrue(McpServersPage.refusal(McpServersEdit.Reason.NO_SUCH_SERVER, "github").contains("«github»"))
  }
}
