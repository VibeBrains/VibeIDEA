// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import com.vibe.lsp.nav.PreciseNavigation

/**
 * Stylus (`.styl`) — третий диалект CSS, которого в открытой платформе нет.
 *
 * Условия включения ему не нужны, в отличие от Tailwind: сервер стартует только на файлах `.styl`,
 * и проект без Stylus не встречает его никогда.
 */
class StylusServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider =
    StylusConnectionProvider(project.basePath)

  // Точная навигация заменяет переход LSP4IJ, и его надо выключить: пока их обработчик
  // отвечает «да» на весь файл, наша точность ничего не изменит. Ключ реестра выключен
  // по умолчанию, поэтому без него поведение прежнее.
  override fun createClientFeatures(): LSPClientFeatures = PreciseNavigation.features()
}

/** Named, not anonymous: the vintage engine cannot name an anonymous subclass and test discovery dies. */
private class StylusConnectionProvider(workingDirectory: String?) :
  ProcessStreamConnectionProvider(ServerBinaries.stylusCommand(), workingDirectory) {
  init { NodeEnvironment.applyTo(this, workingDirectory) }
}
