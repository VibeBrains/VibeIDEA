// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider

/**
 * Stylus (`.styl`) — третий диалект CSS, которого в открытой платформе нет.
 *
 * Условия включения ему не нужны, в отличие от Tailwind: сервер стартует только на файлах `.styl`,
 * и проект без Stylus не встречает его никогда.
 */
class StylusServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider =
    StylusConnectionProvider(project.basePath)
}

/** Named, not anonymous: the vintage engine cannot name an anonymous subclass and test discovery dies. */
private class StylusConnectionProvider(workingDirectory: String?) :
  ProcessStreamConnectionProvider(ServerBinaries.stylusCommand(), workingDirectory) {
  init { NodeEnvironment.applyTo(this, workingDirectory) }
}
