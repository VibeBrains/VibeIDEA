// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.demo

import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.ui.VibeScroll
import com.vibe.agent.ui.composer.PillButton
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.nio.file.Path
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * The Demos window: the runs the `demo` skill recorded, and the chosen report with its video, inside the IDE
 *
 * The report is one self-contained page with the video beside it, so it is shown as the browser shows it, not rebuilt
 * Without JCEF the page opens in the system browser: the list and the buttons still work
 */
class DemoReportsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
  private val model = DefaultListModel<DemoReports.Report>()
  private val list = JBList(model).apply {
    selectionMode = ListSelectionModel.SINGLE_SELECTION
    cellRenderer = ReportRenderer()
    emptyText.text = t("demos.empty", "dir" to DemoReports.DIR)
  }
  private val browser: JBCefBrowser? = if (JBCefApp.isSupported()) JBCefBrowser() else null
  private val placeholder = JBLabel("", SwingConstants.CENTER).apply { foreground = HINT }
  private val cards = java.awt.CardLayout()
  private val viewer = JPanel(cards)

  init {
    browser?.let { Disposer.register(this, it) }
    val actions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
      isOpaque = false
      add(PillButton(t("demos.action.refresh"), outlined = true) { reload() })
      add(PillButton(t("demos.action.browser"), outlined = true) { selected()?.takeIf { it.hasReport }?.let { BrowserUtil.browse(it.report.toFile()) } })
      add(PillButton(t("demos.action.folder"), outlined = true) { selected()?.let { RevealFileAction.openDirectory(it.dir.toFile()) } })
    }
    list.addListSelectionListener { if (!it.valueIsAdjusting) show(selected()) }
    val left = JPanel(BorderLayout()).apply {
      border = JBUI.Borders.empty(8)
      add(actions, BorderLayout.NORTH)
      add(VibeScroll.pane(list), BorderLayout.CENTER)
    }
    browser?.let { viewer.add(it.component, CARD_PAGE) }
    viewer.add(placeholder, CARD_HINT)
    add(JBSplitter(false, SPLIT).apply { firstComponent = left; secondComponent = viewer }, BorderLayout.CENTER)
    show(null)
    reload()
  }

  private fun selected(): DemoReports.Report? = list.selectedValue

  /** Reads the folder off the EDT: a project with many runs lists screenshots of every one */
  fun reload() {
    val base = project.basePath ?: return
    ApplicationManager.getApplication().executeOnPooledThread {
      val reports = runCatching { DemoReports.list(Path.of(base)) }.getOrDefault(emptyList())
      SwingUtilities.invokeLater {
        val keep = selected()?.dir
        model.clear()
        reports.forEach { model.addElement(it) }
        val index = reports.indexOfFirst { it.dir == keep }.takeIf { it >= 0 } ?: if (reports.isEmpty()) -1 else 0
        if (index >= 0) list.selectedIndex = index else show(null)
      }
    }
  }

  private fun show(report: DemoReports.Report?) {
    val hint = when {
      report == null -> t("demos.pick")
      !report.hasReport -> t("demos.noReport", "file" to DemoReports.REPORT)
      browser == null -> t("demos.noJcef")
      else -> null
    }
    if (hint == null) {
      browser!!.loadURL(report!!.report.toUri().toString())
      cards.show(viewer, CARD_PAGE)
    }
    else {
      placeholder.text = hint
      cards.show(viewer, CARD_HINT)
    }
  }

  override fun dispose() {}

  /** One run: its name, when, and what it holds — a run without a report says so before it is clicked */
  private class ReportRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(list: JList<*>, value: Any?, index: Int, selected: Boolean, focus: Boolean): Component {
      val report = value as DemoReports.Report
      val details = listOfNotNull(
        report.stamp,
        if (report.hasVideo) t("demos.item.video") else null,
        t("demos.item.shots", "count" to report.shots).takeIf { report.shots > 0 },
        if (report.hasReport) null else t("demos.item.noReport"),
      ).joinToString(" · ")
      super.getListCellRendererComponent(list, report.name, index, selected, focus)
      text = "<html>${report.name}<br><small>$details</small></html>"
      border = JBUI.Borders.empty(4, 6)
      return this
    }
  }

  companion object {
    private const val SPLIT = 0.3f
    private const val CARD_PAGE = "page"
    private const val CARD_HINT = "hint"
    // The same muted text as the chat's meta line: its contrast is measured in every theme
    private val HINT = JBColor.namedColor("Vibe.Chat.metaForeground", JBColor.GRAY)
  }
}

class DemoReportsToolWindowFactory : ToolWindowFactory, DumbAware {
  /** The stripe title comes from the catalogue: the window's id is ASCII and never translated */
  override fun init(toolWindow: ToolWindow) {
    toolWindow.stripeTitle = t("toolWindow.demos")
  }

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = DemoReportsPanel(project)
    val content = ContentFactory.getInstance().createContent(panel, "", false)
    content.setDisposer(panel)
    toolWindow.contentManager.addContent(content)
    // Opening the window is when a new run is expected: the list is read again rather than watched
    project.messageBus.connect(panel).subscribe(com.intellij.openapi.wm.ex.ToolWindowManagerListener.TOPIC,
      object : com.intellij.openapi.wm.ex.ToolWindowManagerListener {
        override fun toolWindowShown(shown: ToolWindow) { if (shown.id == toolWindow.id) panel.reload() }
      })
  }
}
