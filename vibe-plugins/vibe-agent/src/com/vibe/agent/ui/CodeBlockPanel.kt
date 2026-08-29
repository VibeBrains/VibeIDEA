// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.settings.VibeChatSettings
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * A code block for an agent message's fenced section: real IDE syntax
 * highlighting via a read-only [EditorTextField] when the language tag resolves
 * to a known FileType, otherwise plain monospace. Theme-colored, with a language
 * tag and its own «копировать» button.
 */
class CodeBlockPanel(project: Project?, lang: String?, rawCode: String) : JPanel(BorderLayout()) {
  // Cap a runaway block: a highlighting editor over megabytes of code would freeze the feed.
  private val code = if (rawCode.length > MAX_CODE_CHARS)
    rawCode.take(MAX_CODE_CHARS) + "\n" + t("code.truncated", "count" to (rawCode.length - MAX_CODE_CHARS)) else rawCode
  private val tooBigForHighlight = code.length > MAX_HIGHLIGHT_CHARS

  private val totalLines = code.count { it == '\n' } + 1
  private val foldAt = VibeChatSettings.codeFoldLines
  private val foldable = foldAt > 0 && totalLines > foldAt
  private var expanded = !foldable
  private lateinit var body: JPanel
  private val toggle = ChatTheme.actionLabel("") { toggleFold() }

  init {
    isOpaque = false
    alignmentX = Component.LEFT_ALIGNMENT
    border = JBUI.Borders.compound(JBUI.Borders.empty(2, 0), JBUI.Borders.customLine(BORDER, 1))

    val header = JPanel(BorderLayout()).apply {
      background = HEADER_BG
      isOpaque = true
      add(label(lang?.lowercase() ?: t("code.lang.unknown")), BorderLayout.WEST)
      // Copy always yields the WHOLE block: a folded listing that copies only what is on screen
      // loses code silently, and silent loss is the one failure nobody notices in time.
      add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0)).apply {
        isOpaque = false
        if (foldable) add(toggle)
        add(ChatTheme.copyLabel(t("code.copy")) { code })
      }, BorderLayout.EAST)
    }
    add(header, BorderLayout.NORTH)
    body = JPanel(BorderLayout()).apply { isOpaque = false }
    add(body, BorderLayout.CENTER)
    renderBody(project, lang)
  }

  /**
   * A long listing in the feed pushes the agent's answer off the screen, and the answer is what the
   * person is waiting for. Folded, the block still shows its beginning — a listing collapsed to a
   * single line says nothing about what is inside.
   */
  private fun toggleFold() {
    expanded = !expanded
    renderBody(currentProject, currentLang)
    revalidate(); repaint()
  }

  private fun renderBody(project: Project?, lang: String?) {
    currentProject = project
    currentLang = lang
    val shown = if (expanded) code else code.lineSequence().take(foldAt).joinToString("\n")
    toggle.text = if (expanded) t("code.collapse") else t("code.expand", "count" to (totalLines - foldAt))
    body.removeAll()
    body.add(highlighted(project, lang, shown) ?: plain(shown), BorderLayout.CENTER)
  }

  private fun label(text: String) = JLabel(text).apply {
    font = com.intellij.util.ui.JBFont.label().deriveFont(Font.PLAIN, HEADER_FONT_PT)
    foreground = FG
    border = JBUI.Borders.empty(2, 8)
  }

  /** A read-only highlighting editor, or null when no project / unknown language / too big / creation fails. */
  private fun highlighted(project: Project?, lang: String?, code: String): JComponent? {
    if (project == null || tooBigForHighlight) return null
    val fileName = CodeLangMapping.fileNameFor(lang) ?: return null
    val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
    if (fileType is UnknownFileType) return null
    return try {
      val field = object : EditorTextField(code, project, fileType) {
        override fun createEditor(): EditorEx = super.createEditor().apply {
          setViewer(true)
          isRendererMode = true
          settings.apply {
            isLineNumbersShown = false
            isFoldingOutlineShown = false
            isLineMarkerAreaShown = false
            isCaretRowShown = false
            additionalLinesCount = 0
            additionalColumnsCount = 0
            isUseSoftWraps = false
          }
          setHorizontalScrollbarVisible(true)
          setVerticalScrollbarVisible(false)
          setBorder(JBUI.Borders.empty(4, 6))
        }
      }
      field.setOneLineMode(false)
      field.isViewer = true
      field
    } catch (e: Throwable) {
      null // any editor-creation failure degrades to the plain area
    }
  }

  private fun plain(code: String): JComponent {
    val area = JTextArea(code).apply {
      isEditable = false
      font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(BODY_FONT_PT))
      background = BG
      foreground = FG_CODE
      lineWrap = false
      border = JBUI.Borders.empty(6, 8)
    }
    return com.vibe.agent.ui.VibeScroll.pane(area).apply {
      horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
      verticalScrollBarPolicy = javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
      border = JBUI.Borders.empty()
      isOpaque = false
      viewport.isOpaque = false
    }
  }

  private var currentProject: Project? = null
  private var currentLang: String? = null

  companion object {
    /** Hard cap on rendered code; beyond this the block is truncated with a notice. */
    private const val MAX_CODE_CHARS = 200_000
    /** Above this, skip the highlighting editor (too heavy) and use the plain area. */
    private const val MAX_HIGHLIGHT_CHARS = 40_000
    private const val HEADER_FONT_PT = ChatTheme.CAPTION_FONT_PT
    private const val BODY_FONT_PT = 12f
    // Shared chat-surface tokens: one fallback definition for terminal AND code (ChatTheme).
    private val BG get() = ChatTheme.TERMINAL_BG
    private val HEADER_BG get() = ChatTheme.CARD_BG
    private val FG_CODE get() = ChatTheme.TERMINAL_FG
    private val BORDER get() = ChatTheme.TERMINAL_BORDER
    private val FG get() = ChatTheme.META_FG
  }
}
