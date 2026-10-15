// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.settings

import com.vibe.agent.mcp.ConfiguredServersSource
import com.vibe.agent.mcp.McpServersFile
import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JLabel
import javax.swing.text.JTextComponent

/** An MCP servers page drawn without an IDE: the file is a list in memory and every click is written down */
internal class McpServersPageFixture {
  val toggled = ArrayList<Pair<String, Boolean>>()
  val edited = ArrayList<String?>()
  val deleted = ArrayList<String>()
  val restarted = ArrayList<String>()
  var confirmed = 0
  val added = ArrayList<McpServersFile.Entry>()

  /** What the file refuses an entry with, as the page would be told; null — written */
  var addAnswer: String? = null
  var taken: Set<String> = emptySet()

  val view = McpServersPageView(object : McpServersPageView.Actions {
    override fun toggle(name: String, enabled: Boolean) { toggled += name to enabled }
    override fun edit(name: String?) { edited += name }
    override fun delete(name: String) { deleted += name }
    override fun restart(name: String) { restarted += name }
    override fun confirmTools() { confirmed++ }
    override fun add(entry: McpServersFile.Entry): String? = addAnswer.also { if (it == null) added += entry }
    override fun taken(): Set<String> = taken
  })

  fun entry(name: String, command: String = "npx", args: List<String> = emptyList(), env: Map<String, String> = emptyMap(),
            disabled: Boolean = false) = McpServersFile.Entry(name, command, args, env, disabled)

  fun live(tools: Int = 5, withheld: Boolean = false, alive: Boolean = true) = ConfiguredServersSource.Report.Live(tools, withheld, alive)

  /** Three servers in the three states the page is for: running, not started, off */
  fun threeServers(): List<McpServersPage.Card> {
    val servers = listOf(
      entry("github", args = listOf("-y", "@modelcontextprotocol/server-github"), env = mapOf("GITHUB_PERSONAL_ACCESS_TOKEN" to "ghp_x")),
      entry("tracker", "uvx", listOf("mcp-tracker")),
      entry("черновик", "node", listOf("tools/my-mcp-server.js"), disabled = true),
    )
    val report = ConfiguredServersSource.Report(
      live = mapOf("github" to live(tools = 26)),
      failed = mapOf("tracker" to "не найдена программа uvx — установите её или поправьте command в записи сервера"),
    )
    return McpServersPage.cards(servers, report, canRestart = true)
  }

  fun model(
    cards: List<McpServersPage.Card> = threeServers(),
    warnings: List<String> = emptyList(),
    restarting: Set<String> = emptySet(),
    fileExists: Boolean = true,
  ) = McpServersPageView.Model(cards, warnings, restarting, fileExists)

  fun rendered(model: McpServersPageView.Model = model()): McpServersPageFixture {
    view.render(model)
    return this
  }

  fun textsOf(root: Component): List<String> {
    val out = ArrayList<String>()
    fun walk(c: Component) {
      when (c) {
        is JLabel -> c.text?.let { out += it }
        is AbstractButton -> c.text?.let { out += it }
        // What the person reads: the html of a hint is escaped and wrapped by the editor kit, its document is not
        is JTextComponent -> out += c.document.getText(0, c.document.length).replace(Regex("\\s+"), " ").trim()
      }
      if (c is Container) c.components.forEach { walk(it) }
    }
    walk(root)
    return out
  }

  fun <T : Component> all(root: Component, type: Class<T>): List<T> {
    val out = ArrayList<T>()
    fun walk(c: Component) {
      if (type.isInstance(c)) out += type.cast(c)
      if (c is Container) c.components.forEach { walk(it) }
    }
    walk(root)
    return out
  }

  /** A click on a link: what it does, without the platform's wrapper that wants an application */
  fun click(link: SettingsLink) = link.onClick()

  /** The link with this text anywhere in the page, shown or not */
  fun link(text: String): SettingsLink = all(view.content, SettingsLink::class.java).first { it.text == text }

  fun linksOf(card: FoldPanel): List<String> = all(card, SettingsLink::class.java).mapNotNull { it.text }

  fun foldOf(name: String): FoldPanel = view.cardFolds().first { fold -> textsOf(fold.header).any { it.endsWith("  $name") } }
}
