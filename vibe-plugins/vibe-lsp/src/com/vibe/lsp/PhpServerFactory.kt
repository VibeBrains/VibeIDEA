// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider

/**
 * One PHP server entry, two possible engines behind it — see [PhpEngine] for why there are two.
 *
 * One entry rather than two: LSP4IJ starts every server mapped onto `*.php`, so registering both
 * would run both on the same file and double every completion item, while the person sees no hint
 * that anything is duplicated.
 */
class PhpServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider =
    PhpConnectionProvider(ServerBinaries.phpCommand(), project.basePath)
}

/**
 * Named rather than anonymous on purpose: the JUnit vintage engine scans every class of the
 * module and cannot build a display name for an anonymous subclass, which fails test discovery
 * for the whole module before a single test runs.
 */
private class PhpConnectionProvider(command: List<String>, workingDirectory: String?) :
  ProcessStreamConnectionProvider(command, workingDirectory)
