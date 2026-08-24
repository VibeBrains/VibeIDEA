// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.history

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Loads the chat-history store on a background thread at project open, so the first
 * tool-window paint does not parse a potentially large JSON (base64 images) on the EDT.
 */
class VibeHistoryWarmUp : ProjectActivity {
  override suspend fun execute(project: Project) {
    VibeChatHistory.getInstance()
  }
}
