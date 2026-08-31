// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider

/**
 * ESLint as a language server: the project's own rules shown in the editor as you type.
 *
 * Community has no ESLint integration at all, so there is nothing here to conflict with. What the
 * server DOES need is the project's own ESLint — it runs the config it finds, and a project without
 * one gets a server that starts and reports nothing. That is the honest behaviour: inventing rules
 * the project did not ask for would be worse than silence.
 */
class EslintServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider =
    EslintConnectionProvider(project.basePath)
}

private class EslintConnectionProvider(workingDirectory: String?) :
  ProcessStreamConnectionProvider(ServerBinaries.eslintCommand(), workingDirectory)
