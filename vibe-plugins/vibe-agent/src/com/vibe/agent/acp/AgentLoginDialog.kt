// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.ui.VibeScroll
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The agent asked to sign in: its ways, exactly as it declared them (decision №82).
 *
 * An `agent` method is started by the protocol. A terminal method is the agent's own program: its
 * exact command is on the screen with a copy button, the person runs it in a terminal, and «I signed
 * in — reconnect» starts the agent again, as the spec prescribes after a terminal sign-in. A type we
 * do not know is named and cannot be chosen. No credential passes through this dialog.
 */
class AgentLoginDialog(
  project: Project?,
  private val config: AgentServerConfig,
  private val methods: List<AuthMethod>,
) : DialogWrapper(project) {
  sealed interface Choice {
    /** The protocol drives it: `authenticate` with this method. */
    data class Authenticate(val method: AuthMethod) : Choice

    /** The person signed in outside the IDE: reconnect and initialize again. */
    data object Reconnect : Choice
  }

  private val group = ButtonGroup()
  private val buttons = methods.map { JBRadioButton(it.name) }
  private val details = text("")
  private val copy = JButton(t("auth.copy"))
  private var command: String? = null

  init {
    title = t("auth.title", "agent" to config.name)
    buttons.forEach { button -> group.add(button); button.addActionListener { select() } }
    copy.addActionListener { command?.let { CopyPasteManager.getInstance().setContents(StringSelection(it)) } }
    buttons.withIndex().firstOrNull { methods[it.index].kind != AuthMethod.Kind.OTHER }?.value?.isSelected = true
    select()
    init()
  }

  /** What to do after OK; Cancel means «not now». */
  fun choice(): Choice {
    val method = selected()
    return if (method?.kind == AuthMethod.Kind.AGENT) Choice.Authenticate(method) else Choice.Reconnect
  }

  private fun selected(): AuthMethod? = buttons.indexOfFirst { it.isSelected }.takeIf { it >= 0 }?.let { methods[it] }

  private fun select() {
    val method = selected()
    command = null
    details.text = when {
      methods.isEmpty() -> t("auth.noMethods", "agent" to config.name)
      method == null -> ""
      method.kind == AuthMethod.Kind.AGENT -> describe(method, t("auth.agentMethod"))
      method.kind == AuthMethod.Kind.TERMINAL -> {
        val terminal = AgentAuth.terminalCommand(config, method, SystemInfo.isWindows)
        command = terminal.line
        describe(method, t("auth.terminalMethod", "command" to terminal.line) +
          (if (terminal.entryEnvNames.isEmpty()) "" else "\n\n" + t("auth.entryEnv", "names" to terminal.entryEnvNames.joinToString())))
      }
      else -> describe(method, t("auth.unknownMethod", "type" to method.type))
    }
    details.caretPosition = 0
    copy.isVisible = command != null
    setOKButtonText(if (method?.kind == AuthMethod.Kind.AGENT) t("auth.login") else t("auth.reconnect"))
    isOKActionEnabled = methods.isEmpty() || (method != null && method.kind != AuthMethod.Kind.OTHER)
  }

  private fun describe(method: AuthMethod, how: String): String = listOfNotNull(method.description, how).joinToString("\n\n")

  override fun createCenterPanel(): JComponent {
    val panel = JPanel(BorderLayout(0, JBUI.scale(GAP)))
    panel.add(text(t("auth.intro", "agent" to config.name)), BorderLayout.NORTH)
    val body = JPanel(BorderLayout(0, JBUI.scale(GAP)))
    if (buttons.isNotEmpty()) {
      val list = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
      buttons.forEach { list.add(it) }
      body.add(list, BorderLayout.NORTH)
    }
    body.add(VibeScroll.pane(details), BorderLayout.CENTER)
    body.add(JPanel(BorderLayout()).apply { add(copy, BorderLayout.WEST) }, BorderLayout.SOUTH)
    panel.add(body, BorderLayout.CENTER)
    panel.preferredSize = Dimension(JBUI.scale(WIDTH), JBUI.scale(HEIGHT))
    return panel
  }

  private fun text(value: String) = JBTextArea(value).apply {
    isEditable = false
    lineWrap = true
    wrapStyleWord = true
    isOpaque = false
  }

  private companion object {
    const val GAP = 8
    const val WIDTH = 620
    const val HEIGHT = 380
  }
}
