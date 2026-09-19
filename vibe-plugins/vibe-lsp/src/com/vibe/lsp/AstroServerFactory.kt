// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import com.vibe.lsp.nav.PreciseNavigation

/**
 * Astro: фронтальная часть `.astro` (разметка с выражениями) и его островки компонентов.
 *
 * `typescript.tsdk` здесь ОБЯЗАТЕЛЕН, а не желателен: без него `initialize` отвечает `-32603`
 * «The `typescript.tsdk` init option is required», и язык не работает совсем. Поймано прямым
 * запросом к серверу 18.09.2026 — юнит-тест такого не видит, а человек увидел бы пустой редактор
 * без единого сообщения.
 */
class AstroServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider =
    AstroConnectionProvider(project.basePath)

  // Точная навигация заменяет переход LSP4IJ, и его надо выключить: пока их обработчик
  // отвечает «да» на весь файл, наша точность ничего не изменит. Ключ реестра выключен
  // по умолчанию, поэтому без него поведение прежнее.
  override fun createClientFeatures(): LSPClientFeatures = PreciseNavigation.features()
}

/** Named, not anonymous: the vintage engine cannot name an anonymous subclass and test discovery dies. */
private class AstroConnectionProvider(private val workingDirectory: String?) :
  ProcessStreamConnectionProvider(ServerBinaries.astroCommand(), workingDirectory) {
  init { NodeEnvironment.applyTo(this, workingDirectory) }

  override fun getInitializationOptions(rootFile: VirtualFile?): Any? = tsdkOptions(workingDirectory)
}
