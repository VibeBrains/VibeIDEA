// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent

/**
 * Shared, project-scoped agent state for the status-bar widget. [AgentPanel] pushes
 * transitions here; the widget renders them. Kept tiny and thread-safe (the panel
 * updates from reader/pooled threads).
 */
@Service(Service.Level.PROJECT)
class VibeAgentStatusService {
  enum class State { IDLE, RUNNING, GATE, BLOCKED }

  @Volatile var state: State = State.IDLE
    private set
  @Volatile private var onChange: (() -> Unit)? = null

  fun set(newState: State) {
    if (state == newState) return
    state = newState
    onChange?.invoke()
  }

  fun bind(listener: (() -> Unit)?) { onChange = listener }

  /** Widget label: empty when idle so the widget stays quiet during normal editing. */
  fun label(): String = when (state) {
    State.IDLE -> ""
    State.RUNNING -> "⏳ агент"
    State.GATE -> "🔎 проверка"
    State.BLOCKED -> "⛔ предохранитель"
  }

  companion object {
    fun getInstance(project: Project): VibeAgentStatusService = project.getService(VibeAgentStatusService::class.java)
  }
}

private const val WIDGET_ID = "VibeAgentStatus"

class VibeAgentStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
  private val service = VibeAgentStatusService.getInstance(project)
  private var statusBar: StatusBar? = null

  override fun ID(): String = WIDGET_ID
  override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

  override fun install(statusBar: StatusBar) {
    this.statusBar = statusBar
    service.bind { statusBar.updateWidget(WIDGET_ID) }
  }

  override fun dispose() {
    service.bind(null)
    statusBar = null
  }

  override fun getText(): String = service.label()
  override fun getAlignment(): Float = Component.LEFT_ALIGNMENT
  override fun getTooltipText(): String = "Состояние агента Vibe — нажмите, чтобы открыть чат"
  override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
    ToolWindowManager.getInstance(project).getToolWindow("VibeAgent")?.activate(null)
  }
}

class VibeAgentStatusWidgetFactory : StatusBarWidgetFactory {
  override fun getId(): String = WIDGET_ID
  override fun getDisplayName(): String = "Состояние агента Vibe"
  override fun createWidget(project: Project): StatusBarWidget = VibeAgentStatusWidget(project)
  override fun disposeWidget(widget: StatusBarWidget) = widget.dispose()
}
