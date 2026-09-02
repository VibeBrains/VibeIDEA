// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.background

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/**
 * The project's background jobs, in one place.
 *
 * The registry started inside the chat panel, which was fine while `/bg` was the only way to see
 * it — and stopped being fine the moment a second surface needed the same list. Two registries
 * would mean a job visible in one and invisible in the other, which is worse than no list at all:
 * a person who saw «нет задач» would believe it.
 */
@Service(Service.Level.PROJECT)
class VibeTasksService {
  val registry = TaskRegistry()

  companion object {
    fun getInstance(project: Project): VibeTasksService = project.getService(VibeTasksService::class.java)
  }
}

/** Told when a job starts, ends or is stopped, so an open panel repaints without polling. */
interface TasksChangeListener {
  fun tasksChanged()

  companion object {
    val TOPIC: Topic<TasksChangeListener> = Topic.create("Vibe background tasks", TasksChangeListener::class.java)
  }
}
