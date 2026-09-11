// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.ui.VibeScroll
import java.awt.BorderLayout
import java.awt.Dimension
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
) : DialogWrapper(project) {
  private val list = CheckBoxList<AgentRegistry.Entry>()
  private val details = text("")

  init {
    title = t("registry.action")
    setOKButtonText(if (addable.isEmpty()) t("registry.close") else t("registry.add"))
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
  override fun createActions(): Array<Action> = if (addable.isEmpty()) arrayOf(okAction) else super.createActions()

  /** The entries the person ticked. */
  fun checked(): List<AgentRegistry.Entry> = addable.filter { list.isItemSelected(it) }

  override fun createCenterPanel(): JComponent {
    val panel = JPanel(BorderLayout(0, JBUI.scale(GAP)))
    val head = JPanel(BorderLayout(0, JBUI.scale(GAP)))
    head.add(text(summary), BorderLayout.NORTH)
    if (addable.isNotEmpty()) head.add(text(t("registry.addable", "button" to t("registry.add"))), BorderLayout.SOUTH)
    panel.add(head, BorderLayout.NORTH)
    if (addable.isNotEmpty()) {
      val middle = JPanel(GridLayout(1, 2, JBUI.scale(GAP), 0))
      middle.add(VibeScroll.pane(list))
      middle.add(VibeScroll.pane(details))
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
    details.text = if (index >= 0) list.getItemAt(index)?.let { describe(it) }.orEmpty() else ""
    details.caretPosition = 0
  }

  private fun describe(entry: AgentRegistry.Entry): String {
    val command = AgentRegistry.toAgentEntry(entry)?.let { (listOf(it.command) + it.args).joinToString(" ") }.orEmpty()
    return t("registry.details",
             "name" to entry.name, "version" to (entry.version ?: DASH), "license" to (entry.license ?: DASH),
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
    const val DASH = "—"
  }
}
