// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.vibe.agent.settings.VibeAgentSettings

/**
 * Owns the single [AuditLog] per project, so the chat panel (writer) and the
 * audit-viewer action (reader/exporter/eraser) share one instance and one worker
 * thread — no two uncoordinated logs racing on `.vibe/audit.jsonl` or its rotation.
 */
@Service(Service.Level.PROJECT)
class VibeAuditService(project: Project) : Disposable {
  private val log: AuditLog? = project.basePath?.let {
    AuditLog(it, { VibeAgentSettings.auditEnabled }, { VibeAgentSettings.auditRotationBytes },
      { w -> logger<VibeAuditService>().warn("audit: $w") })
  }

  fun get(): AuditLog? = log

  override fun dispose() {
    log?.close()
  }

  companion object {
    fun getInstance(project: Project): VibeAuditService = project.getService(VibeAuditService::class.java)
  }
}
