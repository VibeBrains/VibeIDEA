// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.settings

import com.intellij.ui.components.JBCheckBox
import com.vibe.agent.mcp.McpServersFile
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the MCP servers page shows and what it does with clicks, driven without an IDE */
class McpServersPageViewTest {
  @Test
  fun `a card per server, in the order of the file, each with its state`() {
    val page = McpServersPageFixture().rendered()
    val titles = page.view.cardFolds().map { fold -> page.textsOf(fold.header).first() }
    assertEquals(3, titles.size)
    assertTrue(titles[0].endsWith("github") && titles[1].endsWith("tracker") && titles[2].endsWith("черновик"), titles.toString())
    val texts = page.textsOf(page.view.content)
    assertTrue("запущен · инструментов: 26" in texts, texts.toString())
    assertTrue(texts.any { it.startsWith("не запустился: не найдена программа uvx") }, texts.toString())
    assertTrue("выключен" in texts, texts.toString())
    assertTrue("серверов: 3 · запущено: 1" in page.textsOf(page.view.header))
  }

  @Test
  fun `a card is closed until opened, except a server that needs the person`() {
    val page = McpServersPageFixture().rendered()
    assertFalse(page.foldOf("github").expanded)
    assertTrue(page.foldOf("tracker").expanded, "a server that did not start shows why without a click")
    assertFalse(page.foldOf("черновик").expanded)
  }

  @Test
  fun `a card the person closed or opened stays so across a redraw`() {
    val page = McpServersPageFixture().rendered()
    page.foldOf("github").expanded = true
    page.foldOf("tracker").expanded = false
    page.view.render(page.model(warnings = listOf("mcp.json: файл не разобрался как JSON")))
    assertTrue(page.foldOf("github").expanded)
    assertFalse(page.foldOf("tracker").expanded, "closed by the person, it must not spring open on the next state change")
  }

  @Test
  fun `the same state is not drawn again, so a report that changes nothing cannot take the focus from the form`() {
    val page = McpServersPageFixture().rendered()
    val before = page.view.cardFolds()
    page.view.render(page.model())
    assertTrue(before.zip(page.view.cardFolds()).all { (a, b) -> a === b }, "the cards were rebuilt for an identical state")
    page.view.render(page.model(restarting = setOf("github")))
    assertFalse(before.first() === page.view.cardFolds().first(), "a changed state must be drawn")
  }

  @Test
  fun `the switch writes the opposite of what the card shows, and a server that is off starts unchecked`() {
    val page = McpServersPageFixture().rendered()
    val boxes = page.all(page.view.content, JBCheckBox::class.java)
    assertEquals(listOf(true, true, false), boxes.map { it.isSelected })
    assertTrue(boxes.all { it.text == "включён" })
    boxes[0].doClick()
    boxes[2].doClick()
    assertEquals(listOf("github" to false, "черновик" to true), page.toggled)
  }

  @Test
  fun `each card offers the links that fit its state`() {
    val page = McpServersPageFixture().rendered()
    assertEquals(listOf("Править", "Перезапустить", "Удалить"), page.linksOf(page.foldOf("github")))
    assertEquals(listOf("Править", "Перезапустить", "Удалить"), page.linksOf(page.foldOf("tracker")))
    assertEquals(listOf("Править", "Удалить"), page.linksOf(page.foldOf("черновик")), "a server that is off has nothing to restart")

    val withheld = page.fixtureCards(withheld = true)
    page.view.render(page.model(withheld))
    assertEquals(listOf("Править", "Перезапустить", "Подтвердить инструменты", "Удалить"), page.linksOf(page.foldOf("github")))
  }

  @Test
  fun `the links do what they say`() {
    val page = McpServersPageFixture().rendered()
    val github = page.foldOf("github")
    page.all(github, SettingsLink::class.java).forEach { page.click(it) }
    assertEquals(listOf<String?>("github"), page.edited)
    assertEquals(listOf("github"), page.restarted)
    assertEquals(listOf("github"), page.deleted)

    page.view.render(page.model(page.fixtureCards(withheld = true)))
    page.click(page.link("Подтвердить инструменты"))
    assertEquals(1, page.confirmed)
  }

  @Test
  fun `a restart under way cannot be started twice`() {
    val page = McpServersPageFixture()
    page.view.render(page.model(restarting = setOf("github")))
    val link = page.all(page.foldOf("github"), SettingsLink::class.java).first { it.text == "Перезапускаю…" }
    assertFalse(link.isEnabled)
    assertTrue(link.isVisible, "the link that says the restart is under way must stay on the page")
    assertTrue(page.all(page.foldOf("tracker"), SettingsLink::class.java).first { it.text == "Перезапустить" }.isEnabled)
  }

  @Test
  fun `the environment is listed by name and the value never reaches the page`() {
    val page = McpServersPageFixture().rendered()
    val texts = page.textsOf(page.foldOf("github")).joinToString("\n")
    assertTrue(texts.contains("GITHUB_PERSONAL_ACCESS_TOKEN"), texts)
    assertFalse(texts.contains("ghp_x"), texts)
    assertTrue(texts.contains("npx -y @modelcontextprotocol/server-github"), texts)
  }

  @Test
  fun `a name with markup in it is shown as text`() {
    val page = McpServersPageFixture()
    val cards = McpServersPage.cards(listOf(page.entry("<b>x</b>", "a<script>", listOf("&"))), page.fixtureReport(), canRestart = true)
    page.view.render(page.model(cards))
    val texts = page.textsOf(page.foldOf("<b>x</b>")).joinToString("\n")
    assertTrue(texts.contains("&lt;script&gt;") || texts.contains("a<script>"), texts)
  }

  @Test
  fun `warnings of the file come first, and the missing file has no link to open`() {
    val page = McpServersPageFixture()
    page.view.render(page.model(cards = emptyList(), warnings = listOf("mcp.json: файл не разобрался как JSON"), fileExists = false))
    assertTrue(page.textsOf(page.view.content).any { it.contains("файл не разобрался как JSON") })
    assertTrue(page.all(page.view.content, SettingsLink::class.java).none { it.text == "Открыть файл" })
    page.view.render(page.model(cards = emptyList(), fileExists = true))
    assertTrue(page.all(page.view.content, SettingsLink::class.java).any { it.text == "Открыть файл" })
  }

  @Test
  fun `the link to the file opens it without an entry`() {
    val page = McpServersPageFixture().rendered()
    page.click(page.link("Открыть файл"))
    assertEquals(listOf<String?>(null), page.edited)
  }

  // ----- empty state -----

  @Test
  fun `no servers is one line of explanation and the way to add the first`() {
    val page = McpServersPageFixture().rendered(McpServersPageFixture().model(cards = emptyList()))
    val texts = page.textsOf(page.view.content).joinToString("\n")
    assertTrue(texts.contains("Своих MCP-серверов нет"), texts)
    assertTrue(texts.contains("docs/vibe/manuals/mcpServersSpec.md"), texts)
    assertTrue(page.view.cardFolds().isEmpty())
    val inContent = page.all(page.view.content, SettingsLink::class.java).count { it.text == "Добавить сервер" }
    val inHeader = page.all(page.view.header, SettingsLink::class.java).count { it.text == "Добавить сервер" }
    assertEquals(1, inContent, "the empty state has its own link")
    assertEquals(1, inHeader, "and the header keeps its own")
  }

  // ----- the form -----

  @Test
  fun `the form is hidden until asked for and the header link opens it`() {
    val page = McpServersPageFixture().rendered()
    assertFalse(page.view.formShown)
    page.click(page.all(page.view.header, SettingsLink::class.java).first { it.text == "Добавить сервер" })
    assertTrue(page.view.formShown)
    assertTrue(page.textsOf(page.view.content).contains("Новый сервер"))
  }

  @Test
  fun `a form with a mistake says it under the fields and writes nothing`() {
    val page = McpServersPageFixture().rendered()
    page.view.showForm()
    page.view.formPanel.type(name = "", command = "npx")
    page.view.formPanel.send()
    assertEquals("Укажите имя сервера", page.view.formPanel.errorText)
    page.taken = setOf("github")
    page.view.formPanel.type(name = "github", command = "npx")
    page.view.formPanel.send()
    assertTrue(page.view.formPanel.errorText.contains("«github»"), page.view.formPanel.errorText)
    assertTrue(page.added.isEmpty())
    assertTrue(page.view.formShown)
  }

  @Test
  fun `a good form sends the entry and closes, empty for the next one`() {
    val page = McpServersPageFixture().rendered()
    page.view.showForm()
    page.view.formPanel.type(name = "jira", command = "uvx", args = "mcp-jira --url \"https://x y\"", env = "JIRA_TOKEN=abc")
    page.view.formPanel.send()
    assertEquals(
      listOf(McpServersFile.Entry("jira", "uvx", listOf("mcp-jira", "--url", "https://x y"), mapOf("JIRA_TOKEN" to "abc"))),
      page.added,
    )
    assertFalse(page.view.formShown)
    assertEquals("", page.view.formPanel.errorText)
    page.view.showForm()
    page.view.formPanel.send()
    assertEquals("Укажите имя сервера", page.view.formPanel.errorText, "the fields were emptied")
  }

  @Test
  fun `a refusal of the file stays under the fields and the form stays open`() {
    val page = McpServersPageFixture().rendered()
    page.addAnswer = "Файл не записан: Read-only file system"
    page.view.showForm()
    page.view.formPanel.type(name = "jira", command = "uvx")
    page.view.formPanel.send()
    assertEquals("Файл не записан: Read-only file system", page.view.formPanel.errorText)
    assertTrue(page.view.formShown)
    assertNull(page.added.firstOrNull())
  }

  @Test
  fun `a redraw while the form is open keeps what was typed`() {
    val page = McpServersPageFixture().rendered()
    page.view.showForm()
    page.view.formPanel.type(name = "jira", command = "uvx", args = "x")
    page.view.render(page.model(), force = true)
    page.addAnswer = "no"
    page.view.formPanel.send()
    assertEquals("no", page.view.formPanel.errorText, "the entry was valid, so the fields survived the redraw")
  }
}

/** The report the fixture's three servers have, with the github server held back when asked */
private fun McpServersPageFixture.fixtureReport(withheld: Boolean = false) = com.vibe.agent.mcp.ConfiguredServersSource.Report(
  live = mapOf("github" to live(tools = 26, withheld = withheld)),
  failed = mapOf("tracker" to "не найдена программа uvx"),
)

private fun McpServersPageFixture.fixtureCards(withheld: Boolean): List<McpServersPage.Card> = McpServersPage.cards(
  listOf(
    entry("github", args = listOf("-y", "@modelcontextprotocol/server-github")),
    entry("tracker", "uvx", listOf("mcp-tracker")),
    entry("черновик", "node", listOf("tools/my-mcp-server.js"), disabled = true),
  ),
  fixtureReport(withheld),
  canRestart = true,
)
