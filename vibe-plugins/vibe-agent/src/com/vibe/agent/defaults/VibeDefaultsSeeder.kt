// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.defaults

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.vibe.agent.settings.VibeAgentSettings

/**
 * Seeds `.vibe/` at project open (background thread by [ProjectActivity]
 * contract). Unconditional by design — see [VibeDefaults]. Runs after the
 * platform settles; repeated opens are cheap (create-if-missing short-circuits).
 */
class VibeDefaultsSeeder : ProjectActivity {
  override suspend fun execute(project: Project) {
    val base = project.basePath ?: return
    val autoUpdate = VibeAgentSettings.seedAutoUpdate
    val report = VibeDefaults.seed(base, autoUpdate = autoUpdate)
    if (report.created > 0 || report.removed.isNotEmpty()) {
      logger<VibeDefaultsSeeder>().info(
        ".vibe defaults seeded: +${report.created}, kept ${report.kept}" +
          (if (report.removed.isNotEmpty()) ", removed stale ${report.removed}" else ""))
    }
    if (report.updated.isNotEmpty()) {
      logger<VibeDefaultsSeeder>().info(
        ".vibe defaults refreshed from release: ${report.updated.map { it.describe() }}")
    }
    if (report.pending.isNotEmpty()) {
      logger<VibeDefaultsSeeder>().info(
        ".vibe defaults left for the person (auto-update is off): ${report.pending.map { it.describe() }}")
    }
    if (report.keptModified.isNotEmpty()) {
      logger<VibeDefaultsSeeder>().warn(
        ".vibe stale seeds kept (user-edited, delete manually if unwanted): ${report.keptModified}")
    }
    if (report.setDrift.isNotEmpty()) {
      logger<VibeDefaultsSeeder>().warn(
        ".vibe set drift — content moved without a revision bump (run bump.mjs in VibeBrains): ${report.setDrift}")
    }
    // The one case seeding cannot decide: their copy differs AND the release moved on.
    if (report.conflicts.isNotEmpty()) {
      logger<VibeDefaultsSeeder>().info(".vibe conflicts: ${report.conflicts.map { it.path }}")
      SeedConflictNotifier.notify(project, base, report.conflicts)
    }
    // Untouched older revisions: applied by the setting, or offered as one explicit step
    SeedUpdateNotifier.notify(project, base, report.updated, report.pending)
  }
}
