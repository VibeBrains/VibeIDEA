// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider

/**
 * CSS, SCSS and LESS — the one frontend language IntelliJ Community does not support at all.
 *
 * Deliberately NOT wired for HTML and JSON, although the same npm package ships servers for them:
 * the platform has its own (`html-tools`, `json` are bundled), and two engines on one file means two
 * sets of completions and two sets of diagnostics, half of which contradict the other half.
 */
class CssServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider =
    CssConnectionProvider(project.basePath)
}

/** Named, not anonymous: the vintage engine cannot name an anonymous subclass and test discovery dies. */
private class CssConnectionProvider(workingDirectory: String?) :
  ProcessStreamConnectionProvider(ServerBinaries.cssCommand(), workingDirectory)
