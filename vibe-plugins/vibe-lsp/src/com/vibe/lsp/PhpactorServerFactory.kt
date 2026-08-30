// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider

class PhpactorServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider {
    return PhpactorConnectionProvider(project.basePath)
  }
}

/**
 * Named rather than anonymous on purpose: the JUnit vintage engine scans every class of the
 * module and cannot build a display name for an anonymous subclass, which fails test discovery
 * for the whole module before a single test runs.
 */
private class PhpactorConnectionProvider(workingDirectory: String?) :
  ProcessStreamConnectionProvider(ServerBinaries.phpactorCommand(), workingDirectory)
