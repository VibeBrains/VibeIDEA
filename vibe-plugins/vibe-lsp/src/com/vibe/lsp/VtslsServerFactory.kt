// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider

/**
 * The TypeScript server: the project's own TypeScript 7 in LSP mode when it has one, the bundled vtsls
 * otherwise — see [TsServerChoice]. The id stays `vtsls` for the registration: it names the language
 * slot, and renaming it would drop every user's per-server LSP4IJ settings.
 */
class VtslsServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider {
    val base = project.basePath?.let { java.nio.file.Path.of(it) }
    val windows = com.vibe.agent.util.ExecutableNames.isWindows()
    val engine = TsServerChoice.forProject(TsServerChoice.stored(), base, windows)
    return VtslsConnectionProvider(TsServerChoice.command(engine, base, windows) { ServerBinaries.vtslsCommand() }, project.basePath)
  }
}

/**
 * Named rather than anonymous on purpose: the JUnit vintage engine scans every class of the
 * module and cannot build a display name for an anonymous subclass, which fails test discovery
 * for the whole module before a single test runs.
 */
private class VtslsConnectionProvider(command: List<String>, workingDirectory: String?) :
  ProcessStreamConnectionProvider(command, workingDirectory)
