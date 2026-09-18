// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider

/**
 * Tailwind CSS: подсказка классов, цвет рядом с классом, объяснение `@apply`.
 *
 * Включается только в проекте, который действительно на Tailwind ([TailwindConfig]). Это не
 * экономия ради экономии: сервер в проекте без Tailwind не делает ничего и при этом занимает
 * память, а человек видит в списке серверов работающий процесс, который ни на что не влияет, —
 * и перестаёт доверять этому списку.
 */
class TailwindServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider =
    TailwindConnectionProvider(project.basePath)

  override fun createClientFeatures(): LSPClientFeatures = object : LSPClientFeatures() {
    override fun isEnabled(file: VirtualFile): Boolean = TailwindConfig.isTailwindProject(project?.basePath)
  }
}

/** Named, not anonymous: the vintage engine cannot name an anonymous subclass and test discovery dies. */
private class TailwindConnectionProvider(workingDirectory: String?) :
  ProcessStreamConnectionProvider(ServerBinaries.tailwindCommand(), workingDirectory) {
  init { NodeEnvironment.applyTo(this, workingDirectory) }
}
