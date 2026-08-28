// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.defaults

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Seeds `.vibe/` at project open (background thread by [ProjectActivity]
 * contract). Unconditional by design — see [VibeDefaults]. Runs after the
 * platform settles; repeated opens are cheap (create-if-missing short-circuits).
 */
class VibeDefaultsSeeder : ProjectActivity {
  override suspend fun execute(project: Project) {
    val base = project.basePath ?: return
    val report = VibeDefaults.seed(base)
    if (report.created > 0) {
      logger<VibeDefaultsSeeder>().info(".vibe defaults seeded: +${report.created}, kept ${report.kept}")
    }
  }
}
