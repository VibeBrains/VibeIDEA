// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graph

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.util.Alarm
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.vibe.agent.graph.ProjectGraphAnalysis.Analysis
import com.vibe.agent.graph.ProjectGraphReport.Row
import com.vibe.agent.graph.ProjectGraphReport.Section
import com.vibe.agent.graphview.CanvasGraph
import com.vibe.agent.graphview.CanvasNode
import com.vibe.agent.graphview.GraphCanvas
import com.vibe.agent.graphview.GraphPalette
import com.vibe.agent.graphview.PlainText
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.ui.VibeScroll
import com.vibe.agent.ui.composer.PillButton
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import java.nio.file.Path
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The project graph tab: the code graph for a person, after graphify (github.com/Graphify-Labs/graphify)
 *
 * The agent has queried this graph through tools; a person never saw it
 * The tab opens on subsystems, not files: a repository is thousands of files, a picture of them all is noise
 * A click opens one subsystem into its most connected files, and a click on a file opens it in the editor
 * The side panel is the report: the files everything flows through, the bridges nobody expects, the files nothing touches
 *
 * The graph is a file that a rebuild writes, so the tab listens to [CodeGraphListener] and reads it again when it changes
 * A single read at opening shows an empty canvas for as long as the build runs
 */
class CodeGraphEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
  private val canvas = GraphCanvas(
    onClick = { node -> onNode(node) },
    fillOf = { key -> GraphPalette.colorOf(key.toIntOrNull() ?: 0) },
  )
  private val search = JBTextField(SEARCH_COLUMNS)
  private val choice = ComboBox<Choice>()
  private val facts = JBCheckBox(t("projectGraph.filter.facts"), true)
  private val guesses = JBCheckBox(t("projectGraph.filter.guesses"), true)
  private val status = PlainText.plain(JBLabel()).apply { border = JBUI.Borders.empty(0, 8, 4, 8) }
  private val back = PillButton(t("projectGraph.back"), outlined = true) { open(null) }
  private val reportModel = DefaultListModel<Row>()
  private val reportList = JBList(reportModel)

  private var analysis: Analysis? = null
  private var builtAtMs = 0L
  private var openId: Int? = null
  private var loaded = false

  /** The first look found no graph: build it once, for the person asked for the picture. A second build is theirs to ask for */
  private var autoBuilt = false
  private var syncingChoice = false

  /** The newest read wins: a slow read that lands after a faster, newer one must not draw over it */
  private val generation = AtomicInteger()

  @Volatile
  private var disposed = false
  private val reloadAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

  private class Choice(val id: Int?, val text: String) {
    override fun toString(): String = text
  }

  private val root = JPanel(BorderLayout())

  init {
    search.emptyText.text = t("projectGraph.search")
    search.document.addDocumentListener(object : DocumentListener {
      override fun insertUpdate(e: DocumentEvent) = onSearch()
      override fun removeUpdate(e: DocumentEvent) = onSearch()
      override fun changedUpdate(e: DocumentEvent) = onSearch()
    })
    facts.toolTipText = t("projectGraph.filter.facts.hint")
    guesses.toolTipText = t("projectGraph.filter.guesses.hint")
    facts.addActionListener { render() }
    guesses.addActionListener { render() }
    choice.setMinimumAndPreferredWidth(JBUI.scale(CHOICE_WIDTH))
    choice.addActionListener {
      if (!syncingChoice) open((choice.selectedItem as? Choice)?.id)
    }
    back.isVisible = false

    val refresh = PillButton(t("projectGraph.refresh"), outlined = true) { CodeGraphTask.start(project) }
    refresh.toolTipText = t("projectGraph.refresh.hint")
    val controls = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
      isOpaque = false
      add(back)
      add(search)
      add(choice)
      add(facts)
      add(guesses)
      add(PillButton(t("projectGraph.fit"), outlined = true) { canvas.fitToScreen() })
      add(refresh)
    }
    root.add(JPanel(BorderLayout()).apply {
      border = JBUI.Borders.empty(6, 0, 0, 0)
      isOpaque = false
      add(controls, BorderLayout.NORTH)
      add(status, BorderLayout.SOUTH)
    }, BorderLayout.NORTH)

    reportList.apply {
      selectionMode = ListSelectionModel.SINGLE_SELECTION
      // A non-null text registers the list with the tooltip manager; the cells then answer with their own
      toolTipText = ""
      cellRenderer = ReportRenderer()
      addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
          val index = locationToIndex(e.point)
          if (index >= 0 && getCellBounds(index, index)?.contains(e.point) == true) act(reportModel.getElementAt(index))
        }
      })
      addKeyListener(object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
          if (e.keyCode == KeyEvent.VK_ENTER) selectedValue?.let { act(it) }
        }
      })
    }
    root.add(OnePixelSplitter(false, SPLITTER_KEY, SPLITTER_PROPORTION).apply {
      firstComponent = canvas
      secondComponent = VibeScroll.pane(reportList)
    }, BorderLayout.CENTER)

    project.messageBus.connect(this).subscribe(CodeGraphListener.TOPIC, CodeGraphListener { scheduleReload(RELOAD_DELAY_MS) })
    render()
    scheduleReload(0)
  }

  /** Index updates come in bursts (a build reports three times); one read per burst */
  private fun scheduleReload(delayMs: Int) {
    if (disposed) return
    reloadAlarm.cancelAllRequests()
    reloadAlarm.addRequest({ readGraph() }, delayMs)
  }

  /** On a pooled thread: reading the file and finding the subsystems takes a moment on a large project */
  private fun readGraph() {
    val ticket = generation.incrementAndGet()
    val cached = CodeGraphRefresh.loadCached(project)
    val next = cached?.let { ProjectGraphAnalysis.analyze(it.graph) }
    ApplicationManager.getApplication().invokeLater {
      if (disposed || ticket != generation.get()) return@invokeLater
      loaded = true
      analysis = next
      builtAtMs = cached?.builtAtMs ?: 0L
      if (openId != null && next?.subsystemById?.containsKey(openId) != true) openId = null
      refreshChoices()
      render()
      renderReport()
      if (next == null && !autoBuilt && !CodeGraphStatus.getInstance(project).building) {
        autoBuilt = true
        CodeGraphTask.start(project)
      }
    }
  }

  private fun filter() = ProjectGraphViews.LinkFilter(facts = facts.isSelected, guesses = guesses.isSelected)

  private fun onSearch() {
    canvas.highlight(search.text)
    updateStatus()
  }

  private fun open(id: Int?) {
    openId = id
    selectChoice()
    render()
  }

  private fun onNode(node: CanvasNode) {
    val subsystem = ProjectGraphViews.subsystemOfNodeId(node.id)
    if (subsystem != null) open(subsystem) else openFile(node.id)
  }

  private fun render() {
    val current = analysis
    val building = CodeGraphStatus.getInstance(project).building
    val open = openId?.let { current?.subsystemById?.get(it) }
    back.isVisible = open != null
    val graph: CanvasGraph = when {
      current == null -> CanvasGraph.EMPTY
      open == null -> ProjectGraphViews.overview(current, filter())
      else -> ProjectGraphViews.subsystemView(current, open.id, filter()).graph
    }
    canvas.show(graph)
    canvas.emptyMessage = emptyMessage(
      ProjectGraphState.emptyState(
        loaded = loaded,
        hasGraph = current != null,
        hasSubsystems = current?.subsystems?.isNotEmpty() == true,
        building = building,
        counted = CodeGraphStatus.getInstance(project).progress != null,
      ))
    canvas.highlight(search.text)
    updateStatus()
  }

  private fun emptyMessage(state: ProjectGraphState.Empty): String = when (state) {
    ProjectGraphState.Empty.NONE -> ""
    ProjectGraphState.Empty.LOADING -> t("projectGraph.empty.loading")
    ProjectGraphState.Empty.BUILDING -> t("projectGraph.empty.building")
    ProjectGraphState.Empty.BUILDING_COUNTED -> {
      val progress = CodeGraphStatus.getInstance(project).progress
      t("projectGraph.empty.buildingCounted", "stale" to (progress?.stale ?: 0), "total" to (progress?.total ?: 0))
    }
    ProjectGraphState.Empty.NO_GRAPH -> t("projectGraph.empty.none")
    ProjectGraphState.Empty.NO_LINKS -> t("projectGraph.empty.noLinks")
  }

  private fun updateStatus() {
    val current = analysis
    val parts = ArrayList<String>()
    val open = openId?.let { current?.subsystemById?.get(it) }
    if (current != null) {
      val report = current.report
      if (open == null) {
        parts.add(t("projectGraph.status.overview", "files" to report.fileCount, "links" to report.linkCount,
                    "facts" to report.facts, "guesses" to report.guesses,
                    "subsystems" to current.subsystems.size, "isolated" to report.isolated.size))
      }
      else {
        val shown = minOf(open.files.size, ProjectGraphViews.SUBSYSTEM_VIEW_LIMIT)
        parts.add(
          if (shown < open.files.size) t("projectGraph.status.limited", "label" to open.label, "files" to open.files.size, "shown" to shown)
          else t("projectGraph.status.subsystem", "label" to open.label, "files" to open.files.size, "links" to open.internalLinks))
      }
      // An old graph is shown as old: a picture of last week's code that does not say so is a lie of omission
      if (builtAtMs > 0) {
        parts.add(t("projectGraph.status.builtAt", "time" to DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(builtAtMs))))
      }
    }
    if (CodeGraphStatus.getInstance(project).building) parts.add(t("projectGraph.status.building"))
    if (search.text.isNotBlank()) parts.add(t("projectGraph.status.found", "count" to canvas.highlightedCount()))
    status.text = parts.joinToString("   ·   ")
  }

  /** Subsystems of the combo are the ones the analysis found, in the order of the report: the largest first */
  private fun refreshChoices() {
    val items = ArrayList<Choice>()
    items.add(Choice(null, t("projectGraph.subsystem.all")))
    analysis?.subsystems?.forEach {
      items.add(Choice(it.id, PlainText.safe(t("projectGraph.subsystem.choice", "label" to it.label, "files" to it.files.size))))
    }
    syncingChoice = true
    try {
      choice.model = CollectionComboBoxModel(items)
      selectChoice()
    }
    finally {
      syncingChoice = false
    }
  }

  private fun selectChoice() {
    val wanted = openId
    val model = choice.model
    syncingChoice = true
    try {
      for (i in 0 until model.size) {
        if ((model.getElementAt(i) as? Choice)?.id == wanted) {
          choice.selectedIndex = i
          break
        }
      }
    }
    finally {
      syncingChoice = false
    }
  }

  private fun renderReport() {
    reportModel.clear()
    analysis?.let { reportModel.addAll(ProjectGraphReport.rows(it)) }
  }

  private fun act(row: Row) {
    when (row) {
      is Row.SubsystemRow -> open(row.id)
      is Row.HubRow -> openFile(row.file)
      is Row.BridgeRow -> openFile(row.from)
      is Row.IsolatedRow -> openFile(row.file)
      is Row.Heading, is Row.More -> {}
    }
  }

  private fun openFile(relative: String) {
    val base = project.basePath ?: return
    val target = LocalFileSystem.getInstance().findFileByNioFile(Path.of(base, relative)) ?: return
    FileEditorManager.getInstance(project).openFile(target, true)
  }

  /** One list for the whole report: headings and rows, a dot in the colour the subsystem has on the picture */
  private inner class ReportRenderer : ListCellRenderer<Row> {
    override fun getListCellRendererComponent(list: JList<out Row>, value: Row, index: Int, selected: Boolean, focused: Boolean): Component {
      val panel = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        isOpaque = true
        background = if (selected && value.isClickable()) list.selectionBackground else list.background
        border = JBUI.Borders.empty(2, 8)
      }
      val foreground = if (selected && value.isClickable()) list.selectionForeground else list.foreground
      val title = PlainText.plain(JBLabel()).apply { this.foreground = foreground }
      val detail = PlainText.plain(JBLabel()).apply {
        this.foreground = if (selected && value.isClickable()) list.selectionForeground else UIUtil.getContextHelpForeground()
      }
      val count = PlainText.plain(JBLabel()).apply { this.foreground = UIUtil.getContextHelpForeground() }
      val text = JPanel(BorderLayout()).apply { isOpaque = false }
      var dot: Int? = null
      when (value) {
        is Row.Heading -> {
          title.text = headingTitle(value.section) + "   " + value.count
          title.font = title.font.deriveFont(Font.BOLD)
          panel.toolTipText = headingHint(value.section)
          panel.border = JBUI.Borders.empty(10, 8, 2, 8)
        }
        is Row.SubsystemRow -> {
          title.text = value.label
          detail.text = t("projectGraph.row.hub", "hub" to value.hub)
          count.text = value.files.toString()
          dot = value.id
          panel.toolTipText = PlainText.safe(value.label + " — " + value.hub)
        }
        is Row.HubRow -> {
          title.text = value.shown
          detail.text = value.subsystemLabel.orEmpty()
          count.text = value.links.toString()
          dot = value.subsystem
          panel.toolTipText = PlainText.safe(value.shown)
        }
        is Row.BridgeRow -> {
          title.text = value.shownFrom + "  →  " + value.shownTo
          detail.text = if (value.bridges == 1) t("projectGraph.row.bridgeOnly", "from" to value.fromLabel, "to" to value.toLabel)
          else t("projectGraph.row.bridgeRare", "count" to value.bridges, "from" to value.fromLabel, "to" to value.toLabel)
          dot = value.fromSubsystem
          panel.toolTipText = PlainText.safe(value.shownFrom + " → " + value.shownTo)
        }
        is Row.IsolatedRow -> {
          title.text = value.shown
          panel.toolTipText = PlainText.safe(value.shown)
        }
        is Row.More -> {
          title.text = t("projectGraph.report.more", "count" to value.count)
          title.foreground = UIUtil.getContextHelpForeground()
        }
      }
      text.add(title, BorderLayout.NORTH)
      if (detail.text?.isNotEmpty() == true) text.add(detail, BorderLayout.SOUTH)
      dot?.let { panel.add(JBLabel(ColorIcon(JBUI.scale(DOT_SIZE), GraphPalette.colorOf(it))), BorderLayout.WEST) }
      panel.add(text, BorderLayout.CENTER)
      if (count.text?.isNotEmpty() == true) panel.add(count, BorderLayout.EAST)
      return panel
    }

    private fun Row.isClickable(): Boolean = this !is Row.Heading && this !is Row.More
  }

  private fun headingTitle(section: Section): String = when (section) {
    Section.SUBSYSTEMS -> t("projectGraph.report.subsystems")
    Section.HUBS -> t("projectGraph.report.hubs")
    Section.SURPRISING -> t("projectGraph.report.surprising")
    Section.ISOLATED -> t("projectGraph.report.isolated")
  }

  private fun headingHint(section: Section): String = when (section) {
    Section.SUBSYSTEMS -> t("projectGraph.report.subsystems.hint")
    Section.HUBS -> t("projectGraph.report.hubs.hint")
    Section.SURPRISING -> t("projectGraph.report.surprising.hint")
    Section.ISOLATED -> t("projectGraph.report.isolated.hint")
  }

  override fun getComponent(): JComponent = root
  override fun getPreferredFocusedComponent(): JComponent = search
  override fun getName(): String = t("projectGraph.tab")
  override fun setState(state: FileEditorState) {}
  override fun isModified(): Boolean = false
  override fun isValid(): Boolean = true
  override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
  override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
  override fun getFile(): VirtualFile = file

  override fun dispose() {
    disposed = true
  }

  private companion object {
    const val SEARCH_COLUMNS = 18
    const val CHOICE_WIDTH = 190
    const val DOT_SIZE = 10

    /** A burst of events (a build reports at its start, at the count and at its end) is read once */
    const val RELOAD_DELAY_MS = 250
    const val SPLITTER_KEY = "vibe.projectGraph.splitter"
    const val SPLITTER_PROPORTION = 0.74f
  }
}

/**
 * The file behind the tab of the picture
 *
 * An editor tab in IntelliJ is always somebody's FILE, not a panel: a light virtual file is the usual way to show something in the centre
 */
class CodeGraphVirtualFile : LightVirtualFile(t("projectGraph.tab")) {
  override fun equals(other: Any?): Boolean = other is CodeGraphVirtualFile
  override fun hashCode(): Int = javaClass.hashCode()
}

class CodeGraphEditorProvider : FileEditorProvider, DumbAware {
  override fun accept(project: Project, file: VirtualFile): Boolean = file is CodeGraphVirtualFile
  override fun createEditor(project: Project, file: VirtualFile): FileEditor = CodeGraphEditor(project, file)
  override fun getEditorTypeId(): String = "vibe-code-graph"
  override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
