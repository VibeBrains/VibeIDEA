// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.settings

import com.vibe.agent.mcp.ConfiguredServersSource
import java.awt.Component
import java.awt.Container
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The MCP servers page has to give way to the window, as every settings page does
 *
 * A card holds lines that are as long as what a person put in the file: the name of a server, a command with a path that has
 * no spaces to break at, the reason a start failed. Each of them is a reason for the page to ask for more width than the dialog
 * has, and a settings page that asks too much is cut at the right edge without a word
 */
class McpServersPageShrinkTest {
  private val longName = "a-server-whose-name-is-as-long-as-a-sentence-about-what-it-does-for-the-project"
  private val longPath = "/Users/someone/Projects/a-very-long-path-without-any-spaces/node_modules/.bin/" +
                         "some-mcp-server-binary-that-keeps-going"
  private val longReason = "не найдена программа some-mcp-server-binary-that-keeps-going — установите её или поправьте command " +
                           "в записи сервера, а если она лежит не в PATH, укажите полный путь"

  private fun fixture(): McpServersPageFixture {
    val fixture = McpServersPageFixture()
    val servers = listOf(
      fixture.entry(longName, longPath, listOf("--config", longPath, "--flag"),
                    mapOf("A_VERY_LONG_VARIABLE_NAME_THAT_KEEPS_GOING_AND_GOING" to "x")),
      fixture.entry("tracker", longPath, listOf("--name", "two words")),
      fixture.entry("github", "npx", listOf("-y", "@modelcontextprotocol/server-github")),
    )
    val report = ConfiguredServersSource.Report(
      live = mapOf("github" to fixture.live(tools = 26)),
      failed = mapOf(longName to longReason, "tracker" to longReason),
    )
    fixture.view.render(fixture.model(McpServersPage.cards(servers, report, canRestart = true), warnings = listOf("mcp.json: $longReason")))
    fixture.view.showForm()
    fixture.view.cardFolds().forEach { it.expanded = true }
    return fixture
  }

  private fun layOut(component: Component) {
    if (component is Container) {
      component.doLayout()
      component.components.forEach { layOut(it) }
    }
  }

  @Test
  fun `long names, paths and reasons do not hold the page wide`() {
    val content = fixture().view.content
    assertTrue(content.minimumSize.width <= MIN_WIDTH_CEILING,
               "the page asks for a minimum of ${content.minimumSize.width} points: it cannot follow a narrow window")
    assertTrue(content.preferredSize.width in 1..PAGE_WIDTH_CEILING,
               "the page asks the dialog for ${content.preferredSize.width} points at a ceiling of $PAGE_WIDTH_CEILING")
  }

  @Test
  fun `the header gives way too`() {
    val header = fixture().view.header
    assertTrue(header.minimumSize.width <= MIN_WIDTH_CEILING, "the title line asks for a minimum of ${header.minimumSize.width} points")
  }

  @Test
  fun `a window of ordinary width is followed and not scrolled sideways`() {
    val page = SettingsUi.page(fixture().view.content)
    page.setSize(ORDINARY_WIDTH, TALL)
    page.doLayout()
    val view = page.viewport.view as javax.swing.Scrollable
    assertTrue(view.scrollableTracksViewportWidth, "the page asks for a sideways bar at $ORDINARY_WIDTH points")
  }

  @Test
  fun `nothing sticks out of the page at a narrow window`() {
    val content = fixture().view.content
    val page = SettingsUi.page(content)
    page.setSize(NARROW_WIDTH, TALL)
    repeat(3) { layOut(page) }

    val wide = ArrayList<String>()
    fun walk(c: Component) {
      if (c.isVisible && c !== page && c !is JScrollPane) {
        val right = SwingUtilities.convertPoint(c.parent, c.x + c.width, 0, content).x
        if (right > content.width) wide += "${c.javaClass.simpleName} ends at $right of ${content.width}"
      }
      if (c is Container) c.components.forEach { walk(it) }
    }
    walk(content)
    assertEquals(emptyList(), wide.take(MAX_REPORTED), "components stick out of the page at $NARROW_WIDTH points")
  }

  private companion object {
    /** The page can be squeezed to about this, which is the narrowest detail pane the settings dialog leaves */
    const val MIN_WIDTH_CEILING = 320

    /** The same order of magnitude the other pages are held to */
    const val PAGE_WIDTH_CEILING = 900
    const val ORDINARY_WIDTH = 660
    const val NARROW_WIDTH = 420
    const val TALL = 4000
    const val MAX_REPORTED = 5
  }
}
