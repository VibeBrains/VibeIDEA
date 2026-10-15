// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.settings

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.mcp.McpServersFile
import com.vibe.agent.ui.VibeScroll
import com.vibe.agent.ui.composer.StatusDot
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * The form that adds a server: name, command, arguments, environment
 *
 * It checks what was typed ([McpServerForm]) and says what is wrong right under the fields; a valid entry goes out through
 * [submit], whose answer is null when the file was written and the reason when it was not, so a refusal stays where the person looks
 * The form never touches the file itself
 */
internal class McpServerFormPanel(
  private val taken: () -> Set<String>,
  private val submit: (McpServersFile.Entry) -> String?,
  private val cancel: () -> Unit,
) : JPanel(BorderLayout()) {
  private val name = JBTextField().apply { emptyText.text = NAME_EXAMPLE }
  private val command = JBTextField().apply { emptyText.text = COMMAND_EXAMPLE }
  private val args = JBTextField().apply { emptyText.text = ARGS_EXAMPLE }
  private val env = JBTextArea(ENV_ROWS, 0).apply { emptyText.text = ENV_EXAMPLE }

  /** What is wrong, in one line that gives way to the width; the full text is in the tooltip */
  private val error = TruncatingLabel().apply { foreground = StatusDot.ERROR_COLOR }

  private val addButton = JButton(t("settings.mcp.form.save")).apply { addActionListener { send() } }
  private val cancelButton = JButton(t("common.cancel")).apply { addActionListener { cancel() } }

  init {
    isOpaque = false
    val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(BUTTON_GAP), 0)).apply {
      isOpaque = false
      add(addButton)
      add(cancelButton)
    }
    val form = FormBuilder.createFormBuilder()
      .addComponent(SettingsUi.section(t("settings.mcp.form.title")))
      .addLabeledComponent(JBLabel(t("settings.mcp.form.name")), name, true)
      .addLabeledComponent(JBLabel(t("settings.mcp.form.command")), command, true)
      .addLabeledComponent(JBLabel(t("settings.mcp.form.args")), args, true)
      .addComponent(SettingsUi.hint(t("settings.mcp.form.argsHint")))
      .addLabeledComponent(JBLabel(t("settings.mcp.form.env")), VibeScroll.pane(env), true)
      .addComponent(SettingsUi.hint(t("settings.mcp.form.envHint")))
      .addComponent(error)
      .addComponent(buttons)
      .panel
    form.isOpaque = false
    add(form, BorderLayout.CENTER)
    // Enter in a one-line field sends the form, as in any dialog
    for (field in listOf(name, command, args)) field.addActionListener { send() }
  }

  /** The cursor goes to the first field, so that the form opens ready to type into */
  fun focusFirst() {
    name.requestFocusInWindow()
  }

  /** Empties the fields and the message: the form is shown again for another server, not for the one just added */
  fun clear() {
    for (field in listOf(name, command, args)) field.text = ""
    env.text = ""
    showError("")
  }

  /** What a test types into the fields */
  internal fun type(name: String, command: String, args: String = "", env: String = "") {
    this.name.text = name
    this.command.text = command
    this.args.text = args
    this.env.text = env
  }

  internal fun send() {
    when (val verdict = McpServerForm.validate(McpServerForm.Input(name.text, command.text, args.text, env.text), taken())) {
      is McpServerForm.Verdict.Invalid -> showError(McpServerForm.describe(verdict))
      is McpServerForm.Verdict.Valid -> showError(submit(verdict.entry).orEmpty())
    }
  }

  internal val errorText: String get() = error.text.orEmpty()

  private fun showError(text: String) {
    error.text = text
    error.isVisible = text.isNotEmpty()
  }

  private companion object {
    const val BUTTON_GAP = 8
    const val ENV_ROWS = 3

    // Code samples, the same in every language: what a server of the usual kind looks like in the file
    const val NAME_EXAMPLE = "github"
    const val COMMAND_EXAMPLE = "npx"
    const val ARGS_EXAMPLE = "-y @modelcontextprotocol/server-github"
    const val ENV_EXAMPLE = "GITHUB_PERSONAL_ACCESS_TOKEN=ghp_…"
  }
}
