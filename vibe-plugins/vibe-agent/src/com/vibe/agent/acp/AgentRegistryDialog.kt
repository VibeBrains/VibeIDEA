// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.BrowserLink
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.ui.VibeScroll
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The catalog as a choice rather than a wall of text.
 *
 * What can be added is ticked and goes into `~/.jetbrains/acp.json`; what cannot — a binary we do
 * not download, an agent already configured — is named with the reason. Nothing is started here:
 * the command is on the screen before anyone runs it.
 */
class AgentRegistryDialog(
  project: Project?,
  private val addable: List<AgentRegistry.Entry>,
  private val notAddable: List<String>,
  private val summary: String,
  private val upgrades: List<AgentRegistry.Upgrade> = emptyList(),
) : DialogWrapper(project) {
  private val list = CheckBoxList<AgentRegistry.Entry>()
  private val upgradeList = CheckBoxList<AgentRegistry.Upgrade>()
  private val details = text("")
  private val links = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), 0))

  /** Something to act on: an agent to add or a pinned one to move. Without it the dialog is a report. */
  private val actionable: Boolean get() = addable.isNotEmpty() || upgrades.isNotEmpty()

  init {
    title = t("registry.action")
    setOKButtonText(if (actionable) t("registry.apply") else t("registry.close"))
    // Not ticked by default: moving a pinned version is the person's decision, the dialog only says there is one.
    upgradeList.setItems(upgrades) { t("registry.upgradeLine", "name" to it.agentName, "from" to it.from, "to" to it.to) }
    list.setItems(addable) { entry ->
      listOfNotNull(entry.name, entry.version).joinToString(" ") +
        (entry.description?.let { " — " + it.take(DESCRIPTION_CHARS) } ?: "")
    }
    list.addListSelectionListener { showDetails() }
    if (addable.isNotEmpty()) list.selectedIndex = 0
    showDetails()
    init()
  }

  /** With nothing to add the dialog is a report: one button that closes it. */
  override fun createActions(): Array<Action> = if (actionable) super.createActions() else arrayOf(okAction)

  /** The entries the person ticked. */
  fun checked(): List<AgentRegistry.Entry> = addable.filter { list.isItemSelected(it) }

  /** The pinned agents the person chose to move to the registry's version. */
  fun checkedUpgrades(): List<AgentRegistry.Upgrade> = upgrades.filter { upgradeList.isItemSelected(it) }

  override fun createCenterPanel(): JComponent {
    val panel = JPanel(BorderLayout(0, JBUI.scale(GAP)))
    val head = JPanel(BorderLayout(0, JBUI.scale(GAP)))
    head.add(text(summary), BorderLayout.NORTH)
    if (addable.isNotEmpty()) head.add(text(t("registry.addable", "button" to t("registry.apply"))), BorderLayout.SOUTH)
    if (upgrades.isNotEmpty()) {
      val box = JPanel(BorderLayout(0, JBUI.scale(GAP / 2)))
      box.add(text(t("registry.upgrades", "button" to t("registry.apply"))), BorderLayout.NORTH)
      box.add(VibeScroll.pane(upgradeList).apply {
        preferredSize = Dimension(JBUI.scale(WIDTH), JBUI.scale(UPGRADES_HEIGHT))
      }, BorderLayout.CENTER)
      head.add(box, BorderLayout.CENTER)
    }
    panel.add(head, BorderLayout.NORTH)
    if (addable.isNotEmpty()) {
      val middle = JPanel(GridLayout(1, 2, JBUI.scale(GAP), 0))
      middle.add(VibeScroll.pane(list))
      val right = JPanel(BorderLayout(0, JBUI.scale(GAP / 2)))
      right.add(VibeScroll.pane(details), BorderLayout.CENTER)
      right.add(links, BorderLayout.SOUTH)
      middle.add(right)
      panel.add(middle, BorderLayout.CENTER)
    }
    if (notAddable.isNotEmpty()) {
      val tail = JPanel(BorderLayout(0, JBUI.scale(GAP / 2)))
      tail.add(text(t("registry.notAddable")), BorderLayout.NORTH)
      tail.add(VibeScroll.pane(text(notAddable.joinToString("\n"))).apply {
        preferredSize = Dimension(JBUI.scale(WIDTH), JBUI.scale(TAIL_HEIGHT))
      }, BorderLayout.CENTER)
      panel.add(tail, if (addable.isNotEmpty()) BorderLayout.SOUTH else BorderLayout.CENTER)
    }
    panel.preferredSize = Dimension(JBUI.scale(WIDTH), JBUI.scale(HEIGHT))
    return panel
  }

  private fun showDetails() {
    val index = list.selectedIndex
    val entry = if (index >= 0) list.getItemAt(index) else null
    details.text = entry?.let { describe(it) }.orEmpty()
    details.caretPosition = 0
    showLinks(entry)
  }

  /**
   * The addresses the registry gave, as links to open: the license text first — the one address the registry requires,
   * and the one a person should read before running someone else's program. Only web addresses become links: the
   * registry is third-party data, and a link is a click away from whatever scheme it names.
   */
  private fun showLinks(entry: AgentRegistry.Entry?) {
    links.removeAll()
    if (entry != null) {
      entry.licenseUrl?.takeIf { AgentRegistry.isWebAddress(it) }?.let { links.add(BrowserLink(t("registry.link.license"), it)) }
      entry.repository?.takeIf { AgentRegistry.isWebAddress(it) }?.let { links.add(BrowserLink(t("registry.link.repository"), it)) }
      entry.website?.takeIf { AgentRegistry.isWebAddress(it) }?.let { links.add(BrowserLink(t("registry.link.website"), it)) }
    }
    links.revalidate()
    links.repaint()
  }

  private fun describe(entry: AgentRegistry.Entry): String {
    val command = AgentRegistry.toAgentEntry(entry)?.let { (listOf(it.command) + it.args).joinToString(" ") }.orEmpty()
    val license = listOfNotNull(entry.license, entry.licenseUrl).joinToString(" · ").ifEmpty { DASH }
    return t("registry.details",
             "name" to entry.name, "version" to (entry.version ?: DASH), "license" to license,
             "website" to (entry.website ?: DASH), "repository" to (entry.repository ?: DASH), "command" to command) +
      (entry.description?.let { "\n\n" + it } ?: "")
  }

  private fun text(value: String) = JBTextArea(value).apply {
    isEditable = false
    lineWrap = true
    wrapStyleWord = true
    isOpaque = false
  }

  private companion object {
    const val DESCRIPTION_CHARS = 80
    const val GAP = 8
    const val WIDTH = 760
    const val HEIGHT = 520
    const val TAIL_HEIGHT = 140
    const val UPGRADES_HEIGHT = 90
    const val DASH = "—"
  }
}
