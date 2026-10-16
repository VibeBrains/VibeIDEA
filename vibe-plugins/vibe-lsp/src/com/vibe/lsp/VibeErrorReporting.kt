// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.lsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.redhat.devtools.lsp4ij.settings.ErrorReportingKind
import com.redhat.devtools.lsp4ij.settings.LanguageServerSettings
import com.redhat.devtools.lsp4ij.settings.LanguageServerSettings.LanguageServerDefinitionSettings
import com.redhat.devtools.lsp4ij.settings.ProjectLanguageServerSettings

/**
 * Failed background requests of our servers go to the server log, not into a balloon — like VS Code does.
 *
 * LSP4IJ has no per-server default: `CancellationSupport` reads the project settings of the server and falls back to
 * `ErrorReportingKind.getDefaultValue()`, which is "As notification", whenever the setting is absent. A background
 * request the server cannot answer (a file that vanished, a config the linter dislikes) is routine, and a balloon per
 * request teaches people to ignore balloons. The log keeps the evidence; "Show Logs" stays one click away.
 *
 * The default is written only into a setting that is absent. A person who picked "As notification" (or "None")
 * in the server's page has a stored value, so it is never overwritten.
 */
class VibeErrorReporting : ProjectActivity {
  override suspend fun execute(project: Project) {
    seed(ProjectLanguageServerSettings.getInstance(project), BranchSwitchRefresh.SERVER_IDS)
  }

  companion object {
    /** What our servers report to when the person has not chosen. */
    val DEFAULT_KIND: ErrorReportingKind = ErrorReportingKind.in_log

    /** Fills the absent error-reporting setting of every listed server; a stored choice is left alone. */
    fun seed(settings: LanguageServerSettings, serverIds: List<String>) {
      for (id in serverIds) {
        val stored = settings.getLanguageServerSettings(id)
        when {
          stored == null -> settings.updateSettings(id, LanguageServerDefinitionSettings().setErrorReportingKind(DEFAULT_KIND))
          stored.errorReportingKind == null -> stored.setErrorReportingKind(DEFAULT_KIND)
        }
      }
    }
  }
}
