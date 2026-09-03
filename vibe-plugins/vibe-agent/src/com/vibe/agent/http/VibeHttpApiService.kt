// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.http

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import com.vibe.agent.settings.VibeAgentSettings

/**
 * Owns the lifetime of the incoming HTTP API: one listener per IDE, started only when the user
 * turned the API on. Off by default — the feature runs code on this machine on request.
 */
@Service(Service.Level.APP)
class VibeHttpApiService {
  private val log = logger<VibeHttpApiService>()

  private val api = VibeHttpApi(
    // Read lazily on every request: regenerating the token must take effect without a restart.
    tokenProvider = { runCatching { VibeApiToken.peek() }.getOrNull() },
    runner = object : VibeHttpApi.Runner {
      override fun run(task: String, sessionId: String?, wait: Boolean): String =
        VibeAgentGateway.getInstance().run(task, sessionId, wait)

      /**
       * Looked up across every open project: a webhook does not know which window is open, and a
       * key that only deduplicated inside one project would let the same delivery start a second
       * run in another window.
       */
      override fun runningWithKey(idempotencyKey: String): String? =
        com.intellij.openapi.project.ProjectManager.getInstance().openProjects
          .asSequence()
          .mapNotNull { project ->
            val runs = com.vibe.agent.runs.VibeAgentRunService.getInstance(project).runs()
            com.vibe.agent.runs.TerritoryLock.existingRun(runs, idempotencyKey)?.runId
          }
          .firstOrNull()
    },
    // MCP speaks to the same project behind the same token: a separate server would mean a second
    // lifecycle, a second port and a second place to get authentication wrong.
    mcpTools = com.vibe.agent.mcp.VibeMcpTools(),
    productVersion = {
      runCatching { com.intellij.openapi.application.ApplicationInfo.getInstance().fullVersion }.getOrNull().orEmpty()
    },
  )

  val port: Int get() = api.boundPort
  val isRunning: Boolean get() = api.isRunning

  /** Brings the listener in line with the settings; safe to call repeatedly. */
  @Synchronized
  fun sync() {
    val wanted = VibeAgentSettings.httpApiEnabled
    if (wanted && !api.isRunning) {
      try {
        api.start(VibeAgentSettings.httpApiPort)
        // Issue the token on first start so «показать токен» always has something to show.
        ApplicationManager.getApplication().executeOnPooledThread { runCatching { VibeApiToken.getOrCreate() } }
        log.info("VibeIDEA HTTP API is listening on 127.0.0.1:${api.boundPort}")
      }
      catch (e: Exception) {
        log.warn("VibeIDEA HTTP API failed to start: ${e.message}")
      }
    }
    else if (!wanted && api.isRunning) {
      api.stop()
      log.info("VibeIDEA HTTP API stopped")
    }
  }

  companion object {
    fun getInstance(): VibeHttpApiService = ApplicationManager.getApplication().service()
  }
}

/**
 * Starts the API at project open when it is enabled, and makes sure the agent tool window exists:
 * the API runs tasks in a real window, and a caller from CI cannot click «открыть панель».
 */
class VibeHttpApiStarter : ProjectActivity {
  override suspend fun execute(project: Project) {
    if (!VibeAgentSettings.httpApiEnabled) return
    VibeHttpApiService.getInstance().sync()
    ApplicationManager.getApplication().invokeLater {
      if (project.isDisposed) return@invokeLater
      // Touching the content manager materialises the tool window content (and with it the panel,
      // which registers itself in the gateway) without stealing focus from the user.
      runCatching { ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.contentManager }
    }
  }

  private companion object {
    const val TOOL_WINDOW_ID = "VibeAgent"
  }
}
