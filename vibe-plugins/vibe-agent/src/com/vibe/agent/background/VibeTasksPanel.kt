// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.background

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.ui.VibeScroll
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * «Фоновые задачи» — what is running right now, and the button that stops it.
 *
 * `/bg` answers the same question in the chat, and that is not the same thing: an answer you have
 * to ask for is one you ask once, at the moment you already suspect something is wrong. A build
 * that has been running for forty minutes is worth noticing before that.
 *
 * The list refreshes on a timer AND on the topic: the topic covers starts and stops, the timer
 * covers the seconds ticking on a job nobody touched.
 */
class VibeTasksPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
  private val list = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }

  private val ticker = Timer(TICK_MS) { render() }.apply { isRepeats = true; start() }

  init {
    border = JBUI.Borders.empty(8)
    val header = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
      isOpaque = false
      add(JBLabel(t("tasks.panel.title")))
      add(ActionLink(t("tasks.panel.stopAll")) { stopAll() })
    }
    add(header, BorderLayout.NORTH)
    add(VibeScroll.pane(list), BorderLayout.CENTER)
    project.messageBus.connect(this).subscribe(TasksChangeListener.TOPIC, object : TasksChangeListener {
      override fun tasksChanged() = SwingUtilities.invokeLater { render() }
    })
    render()
  }

  private fun registry(): TaskRegistry = VibeTasksService.getInstance(project).registry

  private fun stopAll() {
    val running = registry().running()
    val now = System.currentTimeMillis()
    running.forEach { registry().stop(it.id) }
    running.forEach { registry().finish(it.id, TaskRegistry.State.STOPPED, now) }
    render()
  }

  private fun render() {
    list.removeAll()
    val now = System.currentTimeMillis()
    val tasks = registry().all()
    if (tasks.isEmpty()) {
      list.add(JBLabel(t("bg.none")).apply {
        foreground = JBColor.GRAY
        alignmentX = Component.LEFT_ALIGNMENT
      })
    }
    for (task in tasks) {
      list.add(row(task, now))
    }
    list.revalidate()
    list.repaint()
  }

  private fun row(task: TaskRegistry.Task, nowMs: Long): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
    isOpaque = false
    alignmentX = Component.LEFT_ALIGNMENT
    add(JBLabel(t("bg.listLine", "id" to task.id, "state" to state(task.state),
                  "seconds" to task.ageMs(nowMs) / 1000,
                  "command" to task.command.take(COMMAND_LEN))).apply {
      // A finished job is history, not news: grey keeps the running ones legible at a glance.
      if (!task.running) foreground = JBColor.GRAY
    })
    // Only a running job gets a button. A stop link on a finished one would either do nothing or
    // kill a process the operating system has already handed to somebody else.
    if (task.running) {
      add(ActionLink(t("tasks.panel.stop")) {
        registry().stop(task.id)
        registry().finish(task.id, TaskRegistry.State.STOPPED, System.currentTimeMillis())
        render()
      })
    }
  }

  private fun state(state: TaskRegistry.State): String = when (state) {
    TaskRegistry.State.RUNNING -> t("bg.state.running")
    TaskRegistry.State.DONE -> t("bg.state.done")
    TaskRegistry.State.FAILED -> t("bg.state.failed")
    TaskRegistry.State.STOPPED -> t("bg.state.stopped")
    TaskRegistry.State.EXPIRED -> t("bg.state.expired")
  }

  override fun dispose() {
    ticker.stop()
  }

  private companion object {
    /** A second: the number people watch is «сколько уже идёт», and it moves in seconds. */
    const val TICK_MS = 1000
    const val COMMAND_LEN = 50
  }
}

class VibeTasksToolWindowFactory : ToolWindowFactory {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = VibeTasksPanel(project)
    val content = ContentFactory.getInstance().createContent(panel, "", false)
    content.setDisposer(panel)
    toolWindow.contentManager.addContent(content)
  }
}
