// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.settings

import com.vibe.agent.mcp.ConfiguredServersSource
import com.vibe.agent.mcp.McpServersFile
import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JLabel
import javax.swing.text.JTextComponent

/** An MCP servers page drawn without an IDE: the files are lists in memory and every click is written down with the file it went to */
internal class McpServersPageFixture {
  val toggled = ArrayList<Triple<McpServersFile.Layer, String, Boolean>>()
  val edited = ArrayList<Pair<McpServersFile.Layer, String?>>()
  val deleted = ArrayList<Pair<McpServersFile.Layer, String>>()
  val restarted = ArrayList<String>()
  var confirmed = 0
  val added = ArrayList<Pair<McpServersFile.Layer, McpServersFile.Entry>>()

  /** How many times the page asked the outside world for something: a link that leaves this where it was did nothing */
  val requests: Int get() = toggled.size + edited.size + deleted.size + restarted.size + confirmed + added.size

  /** What the file refuses an entry with, as the page would be told; null — written */
  var addAnswer: String? = null

  /** The names each file has, as the form is told when it asks */
  var taken: Map<McpServersFile.Layer, Set<String>> = emptyMap()

  val view = McpServersPageView(object : McpServersPageView.Actions {
    override fun toggle(layer: McpServersFile.Layer, name: String, enabled: Boolean) { toggled += Triple(layer, name, enabled) }
    override fun edit(layer: McpServersFile.Layer, name: String?) { edited += layer to name }
    override fun delete(layer: McpServersFile.Layer, name: String) { deleted += layer to name }
    override fun restart(name: String) { restarted += name }
    override fun confirmTools() { confirmed++ }
    override fun add(layer: McpServersFile.Layer, entry: McpServersFile.Entry): String? =
      addAnswer.also { if (it == null) added += layer to entry }
    override fun taken(layer: McpServersFile.Layer): Set<String> = taken[layer].orEmpty()
  })

  fun entry(name: String, command: String = "npx", args: List<String> = emptyList(), env: Map<String, String> = emptyMap(),
            disabled: Boolean = false, cwd: String? = null) = McpServersFile.Entry(name, command, args, env, disabled, cwd)

  /** The two files laid one over the other, from the entries each has */
  fun merged(project: List<McpServersFile.Entry> = emptyList(), user: List<McpServersFile.Entry> = emptyList()) =
    McpServersFile.merge(McpServersFile.Parsed(user, emptyList()), McpServersFile.Parsed(project, emptyList()))

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
    return McpServersPage.cards(merged(project = servers), report, canRestart = true)
  }

  /**
   * Servers of both files: the shared file's `vibememory` (with a folder of its own), `jira` and `github`, and the project's `github`
   * that replaces the shared one, `tracker` and a switched-off draft
   */
  fun layeredServers(): List<McpServersPage.Card> {
    val user = listOf(
      entry("vibememory", "/Users/me/.vibememory/bin/vibememory-mcp", listOf("--agent", "vibeidea"), cwd = "/Users/me"),
      entry("jira", "uvx", listOf("mcp-jira"), mapOf("JIRA_TOKEN" to "x")),
      entry("github", args = listOf("-y", "@modelcontextprotocol/server-github")),
    )
    val project = listOf(
      entry("github", "npx", listOf("-y", "@modelcontextprotocol/server-github", "--read-only")),
      entry("tracker", "uvx", listOf("mcp-tracker")),
      entry("черновик", "node", listOf("tools/my-mcp-server.js"), disabled = true),
    )
    val report = ConfiguredServersSource.Report(
      live = mapOf("vibememory" to live(tools = 6), "github" to live(tools = 18)),
      failed = mapOf("tracker" to "не найдена программа uvx — установите её или поправьте command в записи сервера"),
    )
    return McpServersPage.cards(merged(project, user), report, canRestart = true)
  }

  fun model(
    cards: List<McpServersPage.Card> = threeServers(),
    warnings: List<String> = emptyList(),
    restarting: Set<String> = emptySet(),
    existing: Set<McpServersFile.Layer> = setOf(McpServersFile.Layer.PROJECT),
  ) = McpServersPageView.Model(cards, warnings, restarting, existing)

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

  /** Lays [root] out at the size of a window, pass after pass: wrapped text asks for another pass once it knows its width */
  fun layOut(root: Component, width: Int, height: Int) {
    root.setSize(width, height)
    repeat(LAYOUT_PASSES) {
      invalidateAll(root)
      doLayoutAll(root)
    }
  }

  private fun invalidateAll(c: Component) {
    c.invalidate()
    if (c is Container) c.components.forEach { invalidateAll(it) }
  }

  private fun doLayoutAll(c: Component) {
    if (c is Container) {
      c.doLayout()
      c.components.forEach { doLayoutAll(it) }
    }
  }

  fun foldOf(name: String): FoldPanel = view.cardFolds().first { fold -> textsOf(fold.header).any { it.endsWith("  $name") } }

  private companion object {
    const val LAYOUT_PASSES = 4
  }
}
