// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Turns off LSP4IJ's «install a language server» banner — once, and only once.
 *
 * The banner offers servers from LSP4IJ's own catalogue by file type, without looking at what is
 * already running. On a `.php` file it offered **Harper**, a grammar checker for English prose. We
 * ship a configured PHP server and start it on that very file; an IDE that simultaneously proposes
 * to fix what already works teaches people to distrust its suggestions.
 *
 * Once, because the second time it would be us overruling the person: if they turn the banner back
 * on, that is their decision, and our own marker key remembers that we have had our say.
 */
class VibeLspSuggestionSilencer : ProjectActivity {
  override suspend fun execute(project: Project) {
    val properties = PropertiesComponent.getInstance()
    if (!SuggestionSilencer.shouldSilence(properties.getBoolean(MARKER_KEY, false))) return
    properties.setValue(MARKER_KEY, true)
    // LSP4IJ's own key, the one its «Dismiss» link writes. Hard-coded from another plugin's
    // internals on purpose, and harmless if it ever changes: the worst case is the banner coming
    // back, not a failure.
    properties.setValue(LSP4IJ_BANNER_KEY, true)
  }

  private companion object {
    const val MARKER_KEY = "vibe.lsp.suggestionBannerDecided"
    const val LSP4IJ_BANNER_KEY = "lsp.install.server.notification.disabled"
  }
}

/** The decision itself, kept pure so «once, and only once» can be tested without an IDE. */
object SuggestionSilencer {
  fun shouldSilence(alreadyDecided: Boolean): Boolean = !alreadyDecided
}
