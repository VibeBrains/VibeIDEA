// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.docs

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Граф документов — в ЦЕНТРАЛЬНОЙ панели, вкладкой редактора.
 *
 * Раньше он жил внутри боковой панели и там же и умирал: в колонке шириной с дерево проекта граф
 * из тридцати узлов превращается в четыре подписи и обрывок линии. Рисунок требует места — значит,
 * ему место там, где место есть, а слева остаётся дерево, отвечающее на вопрос «где что лежит».
 *
 * Концепция подсмотрена у VibeIDE (решение владельца 08.09.2026) и перенесена целиком: поиск по
 * имени с приглушением ненайденного, «вписать в экран», счётчики над рисунком, автовписывание при
 * открытии и зум колесом.
 */
class DocsGraphEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
  private val view = DocsGraphView { path -> openDocument(path) }
  private val counts = JBLabel().apply { border = JBUI.Borders.empty(0, 8) }
  private val search = JBTextField(SEARCH_COLUMNS)

  private val root = JPanel(BorderLayout()).apply {
    val header = JPanel(BorderLayout()).apply {
      border = JBUI.Borders.empty(6, 8)
      isOpaque = false
      add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0)).apply {
        isOpaque = false
        add(search)
        // Кнопка, а не только автовписывание: после зума и таскания вернуться к общему виду — то,
        // что делают чаще всего, и искать это в меню значит не делать вовсе.
        add(com.vibe.agent.ui.composer.PillButton(t("docs.graph.fit"), outlined = true) { view.fitToScreen() })
      }, BorderLayout.WEST)
      add(counts, BorderLayout.EAST)
    }
    add(header, BorderLayout.NORTH)
    add(view, BorderLayout.CENTER)
  }

  init {
    search.emptyText.text = t("docs.graph.search")
    search.document.addDocumentListener(object : javax.swing.event.DocumentListener {
      override fun insertUpdate(e: javax.swing.event.DocumentEvent) = onSearch()
      override fun removeUpdate(e: javax.swing.event.DocumentEvent) = onSearch()
      override fun changedUpdate(e: javax.swing.event.DocumentEvent) = onSearch()
    })
    reload()
  }

  private fun onSearch() {
    view.highlight(search.text)
    updateCounts()
  }

  /** Читает документы заново тем же обходом, что и боковая панель, и рисует граф. */
  fun reload() {
    val prefix = com.vibe.agent.settings.VibeAgentSettings.docsFolder.trim('/')
    com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
      val files = com.vibe.agent.context.ProjectFiles.read(project, setOf("md", "mdx"))
        .filterKeys { prefix.isEmpty() || it.startsWith("$prefix/") }
      val analysis = DocsIndex.analyse(files)
      val layout = DocsGraphLayout.layout(analysis)
      val dropped = DocsGraphLayout.droppedCount(analysis)
      com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
        view.show(layout)
        summary = t("docs.graph.counts",
                    "docs" to analysis.docs.size, "links" to layout.edges.size,
                    "unreachable" to analysis.unreachable.size, "broken" to analysis.brokenLinks.size) +
          // Что рисунок не показал — сказано рядом с ним: картинка, молча упёршаяся в предел,
          // читается как «это всё», а такого она обещать не может.
          (if (dropped > 0) "   " + t("docs.graph.dropped", "count" to dropped) else "")
        updateCounts()
      }
    }
  }

  private var summary: String = ""

  private fun updateCounts() {
    val found = view.highlightedCount()
    counts.text = summary + if (search.text.isBlank()) "" else "   " + t("docs.graph.found", "count" to found)
  }

  private fun openDocument(relative: String) {
    val base = project.basePath ?: return
    val target = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
      .findFileByNioFile(java.nio.file.Path.of(base, relative)) ?: return
    FileEditorManager.getInstance(project).openFile(target, true)
  }

  override fun getComponent(): JComponent = root
  override fun getPreferredFocusedComponent(): JComponent = search
  override fun getName(): String = t("docs.graph.tab")
  override fun setState(state: FileEditorState) {}
  override fun isModified(): Boolean = false
  override fun isValid(): Boolean = true
  override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
  override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
  override fun dispose() {}
  override fun getFile(): VirtualFile = file

  private companion object {
    const val SEARCH_COLUMNS = 18
  }
}

/**
 * Файл-заглушка, за которым живёт вкладка графа.
 *
 * Вкладка редактора в IntelliJ всегда чья-то: платформа открывает ФАЙЛ, а не панель. Лёгкий
 * виртуальный файл — штатный способ показать в центре то, у чего файла нет.
 */
class DocsGraphVirtualFile : LightVirtualFile(t("docs.graph.tab")) {
  override fun equals(other: Any?): Boolean = other is DocsGraphVirtualFile
  override fun hashCode(): Int = javaClass.hashCode()
}

class DocsGraphEditorProvider : FileEditorProvider, DumbAware {
  override fun accept(project: Project, file: VirtualFile): Boolean = file is DocsGraphVirtualFile
  override fun createEditor(project: Project, file: VirtualFile): FileEditor = DocsGraphEditor(project, file)
  override fun getEditorTypeId(): String = "vibe-docs-graph"
  override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
