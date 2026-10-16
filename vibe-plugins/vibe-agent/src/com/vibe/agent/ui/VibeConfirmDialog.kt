// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

/**
 * The one confirmation dialog for text of any length: a command, a URL, a skill's whole description
 *
 * The platform's message dialog sizes itself by its longest line, so a one-line command stretches it across the screen
 * Here the width is fixed, the text wraps, and a long text scrolls inside a capped height
 *
 * [detail] is for what must be read exactly, such as a command: monospace, wrapped at any character
 * Button [buttons] exit with their index, so `exitCode` is the chosen button, and a closed dialog is the cancel code
 */
class VibeConfirmDialog(
  project: Project?,
  private val message: String,
  title: String,
  private val buttons: List<String>,
  private val defaultIndex: Int = 0,
  private val icon: Icon = Messages.getQuestionIcon(),
  private val detail: String? = null,
) : DialogWrapper(project, false) {
  init {
    this.title = title
    isResizable = false
    init()
  }

  override fun createCenterPanel(): JComponent = body(message, detail, icon)

  override fun createActions(): Array<Action> = buttons.mapIndexed { index, name ->
    object : DialogWrapperAction(name) {
      override fun doAction(e: java.awt.event.ActionEvent?) = close(index)
    }.also { if (index == defaultIndex) it.putValue(DEFAULT_ACTION, true) }
  }.toTypedArray()

  override fun getPreferredFocusedComponent(): JComponent? = null

  companion object {
    /** Width of the text column, before scaling. */
    const val TEXT_WIDTH = 560
    private const val MESSAGE_MAX_HEIGHT = 220
    private const val DETAIL_MAX_HEIGHT = 200
    private const val GAP = 10

    /** Shows the dialog and returns the index of the chosen button, or -1 when it was closed without a choice. */
    fun ask(
      project: Project?, message: String, title: String, buttons: List<String>,
      defaultIndex: Int = 0, icon: Icon = Messages.getQuestionIcon(), detail: String? = null,
    ): Int {
      val dialog = VibeConfirmDialog(project, message, title, buttons, defaultIndex, icon, detail)
      dialog.show()
      return dialog.exitCode.takeIf { it in buttons.indices } ?: -1
    }

    /** A yes/no question: true only for the first button. */
    fun confirm(
      project: Project?, message: String, title: String, yes: String, no: String,
      icon: Icon = Messages.getQuestionIcon(), detail: String? = null,
    ): Boolean = ask(project, message, title, listOf(yes, no), 0, icon, detail) == 0

    /** The content of the dialog, apart from the dialog: it can be laid out and looked at without a window. */
    fun body(message: String, detail: String?, icon: Icon): JComponent {
      val column = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        add(textBlock(message, monospace = false, MESSAGE_MAX_HEIGHT))
        if (!detail.isNullOrBlank()) {
          add(javax.swing.Box.createVerticalStrut(JBUI.scale(GAP)))
          add(textBlock(detail, monospace = true, DETAIL_MAX_HEIGHT))
        }
      }
      return JPanel(BorderLayout(JBUI.scale(GAP + 4), 0)).apply {
        isOpaque = false
        add(javax.swing.JLabel(icon).apply { verticalAlignment = javax.swing.SwingConstants.TOP }, BorderLayout.WEST)
        add(column, BorderLayout.CENTER)
      }
    }

    /** A read-only wrapped text of a fixed width, scrolling when it is taller than [maxHeight]. */
    private fun textBlock(text: String, monospace: Boolean, maxHeight: Int): JScrollPane {
      val width = JBUI.scale(TEXT_WIDTH)
      val area = JBTextArea(text).apply {
        isEditable = false
        lineWrap = true
        // Prose breaks at words; a command is read exactly, so it breaks anywhere rather than overflows.
        wrapStyleWord = !monospace
        if (monospace) {
          font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12f))
          background = ChatTheme.TERMINAL_BG
          foreground = ChatTheme.TERMINAL_FG
          border = JBUI.Borders.empty(6, 8)
        }
        else {
          font = UIUtil.getLabelFont()
          isOpaque = false
        }
        setSize(width, Short.MAX_VALUE.toInt())
      }
      val wanted = area.preferredSize.height
      val height = minOf(wanted, JBUI.scale(maxHeight))
      return VibeScroll.pane(area).apply {
        border = JBUI.Borders.empty()
        isOpaque = false
        viewport.isOpaque = monospace
        if (monospace) viewport.background = ChatTheme.TERMINAL_BG
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        alignmentX = JComponent.LEFT_ALIGNMENT
        val size = Dimension(width, height)
        preferredSize = size
        minimumSize = size
        maximumSize = size
      }
    }
  }
}
