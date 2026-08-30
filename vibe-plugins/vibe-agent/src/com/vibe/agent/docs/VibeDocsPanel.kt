// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.docs

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.settings.VibeAgentSettings
import com.vibe.agent.ui.VibeScroll
import java.awt.BorderLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Documents of the project, with the two facts a folder cannot show: what is unreachable from the
 * entry point, and which links lead nowhere.
 *
 * Reading is done off the EDT and once per opening: a documentation folder is hundreds of files in
 * a mature project, and a panel that re-reads them on every repaint is a panel people close.
 */
class VibeDocsPanel(private val project: Project) : JPanel(BorderLayout()) {
  private val model = DefaultListModel<Row>()
  private val list = JBList(model).apply { selectionMode = ListSelectionModel.SINGLE_SELECTION }
  private val summary = JBLabel().apply { border = JBUI.Borders.empty(4, 8) }

  /** One line of the list: the mark says WHY the document is worth attention. */
  private data class Row(val path: String, val label: String) {
    override fun toString(): String = label
  }

  init {
    border = JBUI.Borders.empty(4)
    add(summary, BorderLayout.NORTH)
    add(VibeScroll.pane(list), BorderLayout.CENTER)
    list.addListSelectionListener {
      if (it.valueIsAdjusting) return@addListSelectionListener
      val row = list.selectedValue ?: return@addListSelectionListener
      openDocument(row.path)
    }
    reload()
  }

  fun reload() {
    val base = project.basePath ?: return
    ApplicationManager.getApplication().executeOnPooledThread {
      val root = Path.of(base, VibeAgentSettings.docsFolder)
      val files = readMarkdown(root, base)
      val analysis = DocsIndex.analyse(files)
      ApplicationManager.getApplication().invokeLater {
        model.clear()
        for (doc in analysis.docs) {
          val marks = buildList {
            if (doc.path !in analysis.reachable) add(t("docs.mark.unreachable"))
            val broken = doc.outgoing.count { it.broken }
            if (broken > 0) add(t("docs.mark.broken", "count" to broken))
          }
          val suffix = if (marks.isEmpty()) "" else "   — " + marks.joinToString(", ")
          model.addElement(Row(doc.path, doc.title + "   " + doc.path + suffix))
        }
        summary.text = t("docs.summary", "docs" to analysis.docs.size,
                         "unreachable" to analysis.unreachable.size, "broken" to analysis.brokenLinks.size)
      }
    }
  }

  private fun readMarkdown(root: Path, base: String): Map<String, String> {
    if (!Files.isDirectory(root)) return emptyMap()
    return runCatching {
      Files.walk(root).use { stream ->
        stream.filter { Files.isRegularFile(it) && (it.toString().endsWith(".md") || it.toString().endsWith(".mdx")) }
          .toList()
          .associate { path ->
            Path.of(base).relativize(path).toString().replace('\\', '/') to
              runCatching { Files.readString(path) }.getOrDefault("")
          }
      }
    }.getOrDefault(emptyMap())
  }

  private fun openDocument(relative: String) {
    val base = project.basePath ?: return
    val file = LocalFileSystem.getInstance().findFileByNioFile(Path.of(base, relative)) ?: return
    FileEditorManager.getInstance(project).openFile(file, true)
  }
}

class VibeDocsToolWindowFactory : com.intellij.openapi.wm.ToolWindowFactory, com.intellij.openapi.project.DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: com.intellij.openapi.wm.ToolWindow) {
    val panel = VibeDocsPanel(project)
    val content = com.intellij.ui.content.ContentFactory.getInstance().createContent(panel, null, false)
    toolWindow.contentManager.addContent(content)
  }
}

/** The same analysis as a report, for the case when the panel is not open. */
class VibeDocsReportAction : AnAction({ t("docs.action") }) {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val base = project.basePath ?: return
    ApplicationManager.getApplication().executeOnPooledThread {
      val root = Path.of(base, VibeAgentSettings.docsFolder)
      val files = if (Files.isDirectory(root)) {
        Files.walk(root).use { stream ->
          stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".md") }.toList()
            .associate { Path.of(base).relativize(it).toString().replace('\\', '/') to runCatching { Files.readString(it) }.getOrDefault("") }
        }
      }
      else emptyMap()
      val analysis = DocsIndex.analyse(files)
      val report = buildString {
        appendLine(t("docs.summary", "docs" to analysis.docs.size,
                     "unreachable" to analysis.unreachable.size, "broken" to analysis.brokenLinks.size))
        if (analysis.unreachable.isNotEmpty()) {
          appendLine()
          appendLine(t("docs.report.unreachable"))
          analysis.unreachable.forEach { appendLine("  " + it.path) }
        }
        if (analysis.brokenLinks.isNotEmpty()) {
          appendLine()
          appendLine(t("docs.report.broken"))
          analysis.brokenLinks.forEach { appendLine("  " + it.from + " → " + it.to) }
        }
      }
      ApplicationManager.getApplication().invokeLater {
        com.intellij.openapi.ui.Messages.showInfoMessage(project, report, t("docs.title"))
      }
    }
  }
}
