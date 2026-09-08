// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.docs

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.settings.VibeAgentSettings
import com.vibe.agent.ui.VibeScroll
import java.awt.BorderLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * Documents of the project, with the two facts a folder cannot show: what is unreachable from the
 * entry point, and which links lead nowhere.
 *
 * Reading is done off the EDT and once per opening: a documentation folder is hundreds of files in
 * a mature project, and a panel that re-reads them on every repaint is a panel people close.
 */
class VibeDocsPanel(private val project: Project) : JPanel(BorderLayout()) {
  private val root = DefaultMutableTreeNode()
  private val model = DefaultTreeModel(root)
  private val tree = Tree(model).apply {
    isRootVisible = false
    showsRootHandles = true
    selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
    cellRenderer = DocsTreeRenderer()
  }
  private val summary = JBLabel().apply { border = JBUI.Borders.empty(4, 8) }
  private val graphView = DocsGraphView { openDocument(it) }
  private val cards = JPanel(java.awt.CardLayout())
  private var showingGraph = false



  init {
    border = JBUI.Borders.empty(4)
    val header = JPanel(java.awt.BorderLayout()).apply {
      isOpaque = false
      add(summary, java.awt.BorderLayout.CENTER)
      // The panel used to read the tree only when it was opened: after writing a document one had
      // to close and reopen it to see the link stop being broken.
      add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0)).apply {
        isOpaque = false
        // A list answers «что есть» and hides the shape: thirty tidy files and thirty files with
        // four orphans look identical in a list and nothing alike on a map.
        add(com.intellij.ui.components.ActionLink(t("docs.view.graph")) { toggleView(it.source as com.intellij.ui.components.ActionLink) })
        add(com.intellij.ui.components.ActionLink(t("docs.refresh")) { reload() })
      }, java.awt.BorderLayout.EAST)
    }
    add(header, BorderLayout.NORTH)
    cards.add(VibeScroll.pane(tree), CARD_LIST)
    cards.add(VibeScroll.pane(graphView), CARD_GRAPH)
    add(cards, BorderLayout.CENTER)
    // Открываем по ВЫБОРУ, а не по двойному щелчку: панель заменяет дерево проекта слева, и там
    // одиночный щелчок уже открывает файл — две разные привычки в соседних вкладках хуже одной.
    tree.addTreeSelectionListener {
      val selected = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
      (selected.userObject as? DocsRow)?.node?.path?.let { openDocument(it) }
    }
    reload()
  }

  private fun toggleView(link: com.intellij.ui.components.ActionLink) {
    showingGraph = !showingGraph
    link.text = if (showingGraph) t("docs.view.list") else t("docs.view.graph")
    (cards.layout as java.awt.CardLayout).show(cards, if (showingGraph) CARD_GRAPH else CARD_LIST)
  }

  fun reload() {
    val base = project.basePath ?: return
    ApplicationManager.getApplication().executeOnPooledThread {
      // The same walk as the ui-kit map and the semantic index — and, unlike this panel's own
      // earlier version, one that honours `.vibe/ignore`: a file hidden from the agent had no
      // business being offered here.
      val prefix = VibeAgentSettings.docsFolder.trim('/')
      val files = com.vibe.agent.context.ProjectFiles.read(project, setOf("md", "mdx"))
        .filterKeys { prefix.isEmpty() || it.startsWith("$prefix/") }
      val analysis = DocsIndex.analyse(files)
      val items = analysis.docs.map { doc ->
        DocsTree.Item(
          path = doc.path,
          title = doc.title,
          marks = buildList {
            if (doc.path !in analysis.reachable) add(t("docs.mark.unreachable"))
            val broken = doc.outgoing.count { it.broken }
            if (broken > 0) add(t("docs.mark.broken", "count" to broken))
          },
        )
      }
      val nodes = DocsTree.build(items, root = prefix)
      ApplicationManager.getApplication().invokeLater {
        root.removeAllChildren()
        nodes.forEach { root.add(toSwing(it)) }
        model.reload()
        // Раскрыто целиком: документация — это десятки файлов, а не тысячи, и свёрнутое дерево
        // отвечало бы «папок пять» на вопрос «где что лежит».
        for (i in 0 until tree.rowCount) tree.expandRow(i)
        graphView.show(DocsGraphLayout.layout(analysis))
        val dropped = DocsGraphLayout.droppedCount(analysis)
        summary.text = t("docs.summary", "docs" to analysis.docs.size,
                         "unreachable" to analysis.unreachable.size, "broken" to analysis.brokenLinks.size) +
          // Saying what the drawing left out: a picture that quietly stops at the limit reads as
          // «это всё», which is exactly the claim it cannot make.
          (if (dropped > 0) "   " + t("docs.graph.dropped", "count" to dropped) else "")
      }
    }
  }


  private fun toSwing(node: DocsTree.Node): DefaultMutableTreeNode =
    DefaultMutableTreeNode(DocsRow(node)).also { swing -> node.children.forEach { swing.add(toSwing(it)) } }

  private fun openDocument(relative: String) {
    val base = project.basePath ?: return
    val file = LocalFileSystem.getInstance().findFileByNioFile(Path.of(base, relative)) ?: return
    FileEditorManager.getInstance(project).openFile(file, true)
  }
}

/**
 * Строка дерева: имя, а рядом — то, ради чего панель существует.
 *
 * Заголовок документа приглушён намеренно: имя файла ищут глазами, заголовок читают, когда уже
 * нашли. Пометки («недостижим», «битых ссылок N») выделены цветом внимания — это единственное, чего
 * не покажет дерево проекта, и ради чего сюда приходят.
 *
 * У свёрнутой папки показывается счётчик проблем поддерева: иначе ветка молчит ровно о том, что
 * внутри есть на что посмотреть.
 */
private class DocsTreeRenderer : com.intellij.ui.ColoredTreeCellRenderer() {
  override fun customizeCellRenderer(
    tree: javax.swing.JTree, value: Any?, selected: Boolean,
    expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
  ) {
    val node = ((value as? DefaultMutableTreeNode)?.userObject as? DocsRow)?.node ?: return
    if (node.isFolder) {
      icon = com.intellij.icons.AllIcons.Nodes.Folder
      append(node.name)
      if (node.problems > 0 && !expanded) {
        append("  " + node.problems, com.intellij.ui.SimpleTextAttributes(
          com.intellij.ui.SimpleTextAttributes.STYLE_PLAIN, PROBLEM))
      }
      return
    }
    icon = com.intellij.icons.AllIcons.FileTypes.Text
    append(node.name)
    if (node.title.isNotBlank() && node.title != node.name) {
      append("  " + node.title, com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }
    if (node.marks.isNotEmpty()) {
      append("  " + node.marks.joinToString(", "), com.intellij.ui.SimpleTextAttributes(
        com.intellij.ui.SimpleTextAttributes.STYLE_PLAIN, PROBLEM))
    }
  }

  private companion object {
    /** Цвет внимания — токеном темы: вшитый красный переживёт любую перекраску (правило владельца). */
    val PROBLEM: com.intellij.ui.JBColor
      get() = com.intellij.ui.JBColor.namedColor("Vibe.Docs.problemForeground", com.intellij.ui.JBColor.RED)
  }
}

/** Полезная нагрузка узла: папке путь не нужен, у листа он и есть документ. */
private data class DocsRow(val node: DocsTree.Node) {
  override fun toString(): String = node.name
}

private const val CARD_LIST = "list"
private const val CARD_GRAPH = "graph"

class VibeDocsToolWindowFactory : com.intellij.openapi.wm.ToolWindowFactory, com.intellij.openapi.project.DumbAware {
  /** Подпись — из каталога строк: идентификатор панели ASCII и не переводится, имя переводится. */
  override fun init(toolWindow: com.intellij.openapi.wm.ToolWindow) {
    toolWindow.stripeTitle = com.vibe.agent.i18n.VibeI18n.t("toolWindow.docs")
  }

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
