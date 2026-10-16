// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.settings

import com.intellij.ui.components.JBCheckBox
import com.vibe.agent.mcp.McpServersFile
import org.junit.jupiter.api.Test
import javax.swing.JButton
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
    assertTrue("проект · запущен · инструментов: 26" in texts, texts.toString())
    assertTrue(texts.any { it.startsWith("проект · не запустился: не найдена программа uvx") }, texts.toString())
    assertTrue("проект · выключен" in texts, texts.toString())
    assertTrue("серверов: 3 · запущено: 1" in page.textsOf(page.view.header))
  }

  @Test
  fun `a card is open when first drawn, except a server that is switched off`() {
    val page = McpServersPageFixture().rendered()
    assertTrue(page.foldOf("github").expanded, "the command and the file of a server are in front of the person without a click")
    assertTrue(page.foldOf("tracker").expanded, "a server that did not start shows why without a click")
    assertFalse(page.foldOf("черновик").expanded, "a server that is off has nothing to read")
  }

  @Test
  fun `a card the person closed or opened stays so across a redraw`() {
    val page = McpServersPageFixture().rendered()
    page.foldOf("github").expanded = false
    page.foldOf("черновик").expanded = true
    page.view.render(page.model(warnings = listOf("mcp.json: файл не разобрался как JSON")))
    assertFalse(page.foldOf("github").expanded, "closed by the person, it must not spring open on the next state change")
    assertTrue(page.foldOf("черновик").expanded)
    assertTrue(page.foldOf("tracker").expanded)
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
    val project = McpServersFile.Layer.PROJECT
    assertEquals(listOf(Triple(project, "github", false), Triple(project, "черновик", true)), page.toggled)
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
    assertEquals(listOf<Pair<McpServersFile.Layer, String?>>(McpServersFile.Layer.PROJECT to "github"), page.edited)
    assertEquals(listOf("github"), page.restarted)
    assertEquals(listOf(McpServersFile.Layer.PROJECT to "github"), page.deleted)

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
    val cards = McpServersPage.cards(page.merged(project = listOf(page.entry("<b>x</b>", "a<script>", listOf("&")))), page.fixtureReport(), canRestart = true)
    page.view.render(page.model(cards))
    val texts = page.textsOf(page.foldOf("<b>x</b>")).joinToString("\n")
    assertTrue(texts.contains("&lt;script&gt;") || texts.contains("a<script>"), texts)
  }

  @Test
  fun `warnings of the files come first, and a missing file has no link to open`() {
    val page = McpServersPageFixture()
    page.view.render(page.model(cards = emptyList(), warnings = listOf("mcp.json: файл не разобрался как JSON"), existing = emptySet()))
    assertTrue(page.textsOf(page.view.content).any { it.contains("файл не разобрался как JSON") })
    val links = { page.all(page.view.content, SettingsLink::class.java).mapNotNull { it.text } }
    assertTrue(links().none { it.startsWith("Открыть") })
    page.view.render(page.model(cards = emptyList(), existing = setOf(McpServersFile.Layer.PROJECT)))
    assertTrue("Открыть файл проекта" in links() && "Открыть общий файл" !in links(), links().toString())
    page.view.render(page.model(cards = emptyList(), existing = McpServersFile.Layer.entries.toSet()))
    assertTrue("Открыть файл проекта" in links() && "Открыть общий файл" in links(), links().toString())
  }

  @Test
  fun `each link to a file opens that file without an entry`() {
    val page = McpServersPageFixture()
    page.view.render(page.model(existing = McpServersFile.Layer.entries.toSet()))
    page.click(page.link("Открыть файл проекта"))
    page.click(page.link("Открыть общий файл"))
    assertEquals(listOf<Pair<McpServersFile.Layer, String?>>(
      McpServersFile.Layer.PROJECT to null, McpServersFile.Layer.USER to null), page.edited)
  }

  // ----- the two files -----

  @Test
  fun `a card shows the file the server is from, and a project entry over a shared one says so`() {
    val page = McpServersPageFixture()
    page.view.render(page.model(page.layeredServers(), existing = McpServersFile.Layer.entries.toSet()))
    fun header(name: String) = page.textsOf(page.foldOf(name).header).joinToString("|")
    assertTrue(header("vibememory").contains("|общий · запущен · инструментов: 6|"), header("vibememory"))
    assertTrue(header("jira").contains("|общий · ещё не запускался|"), header("jira"))
    assertTrue(header("github").contains("|проект · перекрывает общую · запущен · инструментов: 18|"), header("github"))
    assertTrue(header("tracker").contains("|проект · не запустился"), header("tracker"))
    val body = { name: String -> page.textsOf(page.foldOf(name)).joinToString("\n") }
    assertTrue(body("jira").contains("Файл: ~/.vibe/mcp.json"), body("jira"))
    assertTrue(body("github").contains("Файл: .vibe/mcp.json"), body("github"))
    assertTrue(body("github").contains("заменяет запись «github» общего файла целиком"), body("github"))
    assertFalse(body("tracker").contains("заменяет запись"), body("tracker"))
  }

  @Test
  fun `the working folder is shown on the card that has one`() {
    val page = McpServersPageFixture()
    page.view.render(page.model(page.layeredServers()))
    assertTrue(page.textsOf(page.foldOf("vibememory")).joinToString("\n").contains("Рабочий каталог: /Users/me"))
    assertFalse(page.textsOf(page.foldOf("jira")).joinToString("\n").contains("Рабочий каталог"))
  }

  @Test
  fun `every action of a card goes to the file the card is from`() {
    val page = McpServersPageFixture()
    page.view.render(page.model(page.layeredServers()))
    val user = McpServersFile.Layer.USER
    val project = McpServersFile.Layer.PROJECT
    page.all(page.foldOf("jira"), JBCheckBox::class.java).single().doClick()
    page.all(page.foldOf("tracker"), JBCheckBox::class.java).single().doClick()
    assertEquals(listOf(Triple(user, "jira", false), Triple(project, "tracker", false)), page.toggled)
    page.all(page.foldOf("jira"), SettingsLink::class.java).forEach { page.click(it) }
    page.all(page.foldOf("github"), SettingsLink::class.java).forEach { page.click(it) }
    assertEquals(listOf<Pair<McpServersFile.Layer, String?>>(user to "jira", project to "github"), page.edited)
    assertEquals(listOf(user to "jira", project to "github"), page.deleted)
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

  @Test
  fun `every link of the page does something, so a click on one is never lost`() {
    val page = McpServersPageFixture()
    page.view.render(page.model(page.fixtureCards(withheld = true), existing = McpServersFile.Layer.entries.toSet()))
    val links = page.all(page.view.header, SettingsLink::class.java) + page.all(page.view.content, SettingsLink::class.java)
    // The header, two links to the files, and from two to four links on each of the three cards
    assertTrue(links.size >= 12, links.map { it.text }.toString())
    for (link in links) {
      val requestsBefore = page.requests
      page.click(link)
      assertTrue(page.requests > requestsBefore || page.view.formShown, "the link «${link.text}» did nothing")
      page.view.hideForm()
    }
  }

  @Test
  fun `the link of the empty page that adds a server opens the form`() {
    val page = McpServersPageFixture().rendered(McpServersPageFixture().model(cards = emptyList()))
    page.click(page.all(page.view.content, SettingsLink::class.java).first { it.text == "Добавить сервер" })
    assertTrue(page.view.formShown)
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
    page.taken = mapOf(McpServersFile.Layer.PROJECT to setOf("github"))
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
      listOf(McpServersFile.Layer.PROJECT to
               McpServersFile.Entry("jira", "uvx", listOf("mcp-jira", "--url", "https://x y"), mapOf("JIRA_TOKEN" to "abc"))),
      page.added,
      "the project's file is the default",
    )
    assertFalse(page.view.formShown)
    assertEquals("", page.view.formPanel.errorText)
    page.view.showForm()
    page.view.formPanel.send()
    assertEquals("Укажите имя сервера", page.view.formPanel.errorText, "the fields were emptied")
  }

  @Test
  fun `the form writes into the file it was told to, and checks the names of that file only`() {
    val page = McpServersPageFixture().rendered()
    page.taken = mapOf(McpServersFile.Layer.USER to setOf("github"))
    page.view.showForm()
    page.view.formPanel.pick(McpServersFile.Layer.USER)
    page.view.formPanel.type(name = "github", command = "npx")
    page.view.formPanel.send()
    assertTrue(page.view.formPanel.errorText.contains("«github»"), "the name is taken in the shared file")
    page.view.formPanel.pick(McpServersFile.Layer.PROJECT)
    page.view.formPanel.send()
    assertEquals(listOf(McpServersFile.Layer.PROJECT to McpServersFile.Entry("github", "npx")), page.added,
                 "the same name in the project's file is free: it replaces the shared one")
    page.view.showForm()
    page.view.formPanel.type(name = "pg", command = "node", cwd = " /Users/me ")
    page.view.formPanel.pick(McpServersFile.Layer.USER)
    page.view.formPanel.send()
    assertEquals(McpServersFile.Layer.USER to McpServersFile.Entry("pg", "node", cwd = "/Users/me"), page.added.last())
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

  @Test
  fun `the form is drawn after the cards, where a person who has read the list looks next`() {
    val page = McpServersPageFixture().rendered()
    page.view.showForm()
    val cards = page.view.content.components.filterIsInstance<CardPanel>()
    assertEquals(page.view.cardFolds().size + 1, cards.size)
    assertTrue(page.all(cards.last(), McpServerFormPanel::class.java).isNotEmpty(), "the form is the last card")
    assertTrue(cards.dropLast(1).all { page.all(it, McpServerFormPanel::class.java).isEmpty() }, "no card but the last holds the form")
  }

  @Test
  fun `the form can be cancelled from its title and from its bottom, and nothing is written`() {
    val ways = listOf<(McpServersPageFixture) -> Unit>(
      { page -> page.click(page.all(page.view.formPanel, SettingsLink::class.java).single { it.text == "Отмена" }) },
      // A link is a button too, to the platform: the one at the bottom is the button that is not a link
      { page -> page.all(page.view.formPanel, JButton::class.java).single { it.text == "Отмена" && it !is SettingsLink }.doClick() },
    )
    for (cancel in ways) {
      val page = McpServersPageFixture().rendered()
      page.view.showForm()
      page.view.formPanel.type(name = "jira", command = "uvx")
      cancel(page)
      assertFalse(page.view.formShown)
      assertTrue(page.added.isEmpty(), "a cancelled form writes nothing")
      assertTrue(page.all(page.view.content, McpServerFormPanel::class.java).isEmpty(), "the form is gone from the page")
      page.view.showForm()
      page.view.formPanel.send()
      assertEquals("Укажите имя сервера", page.view.formPanel.errorText, "the fields were emptied by the cancel")
    }
  }

  @Test
  fun `the way out of the form is on its title line, in sight without scrolling a form taller than the window`() {
    val page = McpServersPageFixture().rendered()
    page.view.showForm()
    page.layOut(SettingsUi.page(page.view.content), FORM_WIDTH, FORM_HEIGHT)
    val form = page.view.formPanel
    val cancel = page.all(form, SettingsLink::class.java).single { it.text == "Отмена" }
    val firstField = page.all(form, javax.swing.JTextField::class.java).first()
    val linkTop = javax.swing.SwingUtilities.convertPoint(cancel, 0, 0, form).y
    val fieldTop = javax.swing.SwingUtilities.convertPoint(firstField, 0, 0, form).y
    assertTrue(linkTop < fieldTop, "the cancel link is at $linkTop, the first field at $fieldTop")
  }

  @Test
  fun `showing the form scrolls the page to it`() {
    val page = McpServersPageFixture().rendered()
    val scroll = SettingsUi.page(page.view.content)
    page.layOut(scroll, FORM_WIDTH, FORM_HEIGHT)
    page.view.showForm()
    page.layOut(scroll, FORM_WIDTH, FORM_HEIGHT)
    val card = generateSequence<java.awt.Component>(page.view.formPanel) { it.parent }.first { it is CardPanel }
    assertTrue(card.y > FORM_HEIGHT, "the form must start below the first screen for the scroll to mean anything, it starts at ${card.y}")
    page.view.revealForm()
    assertEquals(card.y, scroll.viewport.viewPosition.y, "the top of the form is the top of what the person sees")
  }
}

/** The report the fixture's three servers have, with the github server held back when asked */
private fun McpServersPageFixture.fixtureReport(withheld: Boolean = false) = com.vibe.agent.mcp.ConfiguredServersSource.Report(
  live = mapOf("github" to live(tools = 26, withheld = withheld)),
  failed = mapOf("tracker" to "не найдена программа uvx"),
)

private fun McpServersPageFixture.fixtureCards(withheld: Boolean): List<McpServersPage.Card> = McpServersPage.cards(
  merged(project = listOf(
    entry("github", args = listOf("-y", "@modelcontextprotocol/server-github")),
    entry("tracker", "uvx", listOf("mcp-tracker")),
    entry("черновик", "node", listOf("tools/my-mcp-server.js"), disabled = true),
  )),
  fixtureReport(withheld),
  canRestart = true,
)

/** The size of the settings window the form tests lay the page out in: short enough that the form starts below the first screen */
private const val FORM_WIDTH = 660
private const val FORM_HEIGHT = 300
