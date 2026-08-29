// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.runs

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.vibe.agent.ui.VibeScroll
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * «Диспетчерская»: who ran what while nobody was looking, and how it ended.
 *
 * Answers a question the chat cannot: an HTTP-triggered task or a pipeline may outlive the window
 * that started it, and until this panel existed the only trace was a chat thread nobody knew to
 * open. The counters of «требует внимания» are always rendered, zeros included — an absent line
 * reads as "nothing to worry about", which is exactly the claim we cannot make by omission.
 */
class VibeRunsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
  private enum class Filter { ALL, RUNNING, FINISHED, ATTENTION }

  private var filter = Filter.ALL
  private val search = SearchTextField()
  private val summaryLabel = JBLabel()
  private val list = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
  private var loaded: List<AgentRunLedger.Run> = emptyList()

  init {
    border = JBUI.Borders.empty(8)
    val filters = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
      isOpaque = false
      add(link("Все") { filter = Filter.ALL; render() })
      add(link("Работают") { filter = Filter.RUNNING; render() })
      add(link("Завершены") { filter = Filter.FINISHED; render() })
      add(link("Требуют внимания") { filter = Filter.ATTENTION; render() })
      add(link("Обновить") { reload() })
    }
    search.textEditor.document.addDocumentListener(object : javax.swing.event.DocumentListener {
      override fun insertUpdate(e: javax.swing.event.DocumentEvent) = render()
      override fun removeUpdate(e: javax.swing.event.DocumentEvent) = render()
      override fun changedUpdate(e: javax.swing.event.DocumentEvent) = render()
    })
    val header = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
      add(summaryLabel.apply { alignmentX = Component.LEFT_ALIGNMENT })
      add(filters.apply { alignmentX = Component.LEFT_ALIGNMENT })
      add(search.apply { alignmentX = Component.LEFT_ALIGNMENT })
    }
    add(header, BorderLayout.NORTH)
    add(VibeScroll.pane(list), BorderLayout.CENTER)
    reload()
  }

  private fun link(text: String, action: () -> Unit) = ActionLink(text) { action() }

  fun reload() {
    ApplicationManager.getApplication().executeOnPooledThread {
      val runs = VibeAgentRunService.getInstance(project).runs()
      SwingUtilities.invokeLater {
        loaded = runs
        render()
      }
    }
  }

  private fun render() {
    val summary = AgentRunLedger.summarize(loaded)
    // Zeros are printed on purpose: «брошенных 0» is an answer, silence is not.
    summaryLabel.text = "<html>Прогонов: ${loaded.size} · работают ${summary.running} · завершены ${summary.completed}" +
                        " · <b>требуют внимания ${summary.attention}</b> (брошенных ${summary.orphaned}, с ошибкой ${summary.failed})</html>"
    val visible = AgentRunLedger.search(loaded, search.text).filter { run ->
      when (filter) {
        Filter.ALL -> true
        Filter.RUNNING -> run.status == AgentRunLedger.Status.RUNNING
        Filter.FINISHED -> run.isFinished && !run.needsAttention
        Filter.ATTENTION -> run.needsAttention
      }
    }
    list.removeAll()
    if (visible.isEmpty()) list.add(hint(emptyText()))
    else visible.sortedByDescending { it.startedAtMs }.forEach { list.add(row(it)) }
    list.add(hint("В журнал попадают только прогоны без наблюдения — задачи HTTP API и пайплайны; " +
                  "обычная переписка живёт в истории чатов. Пишутся только метаданные: ни промптов, ни ответов модели."))
    list.revalidate(); list.repaint()
  }

  private fun emptyText(): String = when (filter) {
    Filter.ATTENTION -> "Прогонов, требующих внимания, нет."
    Filter.RUNNING -> "Сейчас ничего не выполняется."
    Filter.FINISHED -> "Завершённых прогонов пока нет."
    Filter.ALL -> "Журнал пуст: ни одной задачи HTTP API и ни одного пайплайна ещё не запускалось."
  }

  private fun hint(text: String) = JBLabel("<html>$text</html>").apply {
    foreground = JBColor.GRAY
    font = JBFont.label().deriveFont(11f)
    border = JBUI.Borders.empty(6, 2)
    alignmentX = Component.LEFT_ALIGNMENT
  }

  private fun row(run: AgentRunLedger.Run): JComponent {
    val status = when (run.status) {
      AgentRunLedger.Status.RUNNING -> "выполняется"
      AgentRunLedger.Status.COMPLETED -> "завершён"
      AgentRunLedger.Status.FAILED -> "ошибка"
      AgentRunLedger.Status.CANCELLED -> "отменён"
      AgentRunLedger.Status.ORPHANED -> "брошен"
    }
    val source = if (run.source == AgentRunLedger.Source.HTTP_API) "HTTP API" else "пайплайн"
    val steps = run.maxSteps?.let { "${run.steps}/$it" } ?: run.steps.toString()
    val when_ = TIME.format(Date(run.startedAtMs))
    val details = buildString {
      append(source).append(" · ").append(status)
      run.outcome?.let { append(" — ").append(it) }
      append(" · шагов ").append(steps)
      if (run.changedFiles > 0) append(" · файлов ").append(run.changedFiles)
      run.target?.let { append(" · ").append(it) }
      append(" · ").append(when_)
    }
    return JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
      alignmentX = Component.LEFT_ALIGNMENT
      border = JBUI.Borders.empty(4, 2)
      add(JBLabel(run.goal.ifBlank { "(без цели)" }).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        foreground = if (run.needsAttention) JBColor.namedColor("Vibe.Runs.attention", JBColor.RED) else foreground
      })
      add(JBLabel(details).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        foreground = JBColor.GRAY
        font = JBFont.label().deriveFont(11f)
      })
    }
  }

  override fun dispose() {}

  private companion object {
    val TIME = SimpleDateFormat("dd.MM HH:mm")
  }
}

class VibeRunsToolWindowFactory : ToolWindowFactory {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = VibeRunsPanel(project)
    val content = ContentFactory.getInstance().createContent(panel, "", false)
    content.setDisposer(panel)
    toolWindow.contentManager.addContent(content)
  }
}
